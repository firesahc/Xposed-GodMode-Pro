package com.kaisar.xposed.godmode.engine.matcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.testing.ActivityTestHost;
import com.kaisar.xposed.godmode.engine.util.GmConstants;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link CompositeMatcher} 匹配结果 golden 测试 — 锁定 docs 合同中
 * "当前 matcher 的匹配结果" 不变项。
 * <p>
 * 覆盖矩阵（每条匹配语义一正一反）：
 * <ul>
 *   <li>resourceId 锚定命中/未命中（framework id "android:id/content"）</li>
 *   <li>depth 路径锚定（真实 {@link ViewTraversal#getViewHierarchyDepth} 链）</li>
 *   <li>{@link CompositeMatcher#isStructuralMatch} 全字段 AND（strictParent 双模式）</li>
 *   <li>structuralRepeatable：repeatable + 有效 itemPath 跳过 text/description 检查</li>
 *   <li>CARD vs ELEMENT：CARD 跳过隐藏卡片根；返回值与 ELEMENT 同为 itemPath 导航目标
 *       （卡片根提升由上游 ViewController.resolveCardTarget 负责，不在 matcher 层）</li>
 *   <li>TAG_GM_CMP 排除：锚定路径经 isVisibleView 排除；信息流扫描当前不查 tag</li>
 *   <li>matchAllViewsBatch 多规则共享遍历 + {@link GmConstants#MAX_REPEATABLE_RESULTS} 上限</li>
 *   <li>viewType 反射过滤命中/排除</li>
 *   <li>invalidateRecyclerCache 缓存失效语义</li>
 * </ul>
 */
@RunWith(AndroidJUnit4.class)
public final class CompositeMatcherInstrumentedTest {

    @Before
    public void startTestHost() {
        ActivityTestHost.requireResumed(CompositeMatcherTestActivity.class);
    }

    @After
    public void finishTestHost() {
        ActivityTestHost.finishResumed(CompositeMatcherTestActivity.class);
    }

    @Test
    public void resourceIdAnchorFindsFrameworkContentViewById() throws Exception {
        withActivity(activity -> {
                resetHostTree(activity);
                View decor = activity.getWindow().getDecorView();

                MatchSpec hit = new MatchSpec.Builder()
                        .resourceName(activity.getResources().getResourceName(android.R.id.content))
                        .build();
                assertSame(decor.findViewById(android.R.id.content),
                        new CompositeMatcher().matchView(decor, hit));

                // An anchor hit still goes through AND validation.
                assertNull(new CompositeMatcher().matchView(decor,
                        hit.toBuilder().viewClass(android.widget.Button.class.getName()).build()));

                // Unknown resource name resolves to 0 and yields no anchor.
                assertNull(new CompositeMatcher().matchView(decor,
                        new MatchSpec.Builder().resourceName("gm_no_such_resource").build()));

                // Rules persist canonical package:type/name resource identifiers;
                // a bare framework entry name is not an unambiguous anchor.
                assertNull(new CompositeMatcher().matchView(decor,
                        new MatchSpec.Builder().resourceName("content").build()));
        });
    }

    @Test
    public void depthAnchorResolvesRealHierarchyPathThenValidatesStructurally() throws Exception {
        withActivity(activity -> {
                resetHostTree(activity);
                LinearLayout container = new LinearLayout(activity);
                TextView target = new TextView(activity);
                container.addView(target);
                activity.mount(container);

                int[] realDepth = ViewTraversal.getViewHierarchyDepth(target);

                assertSame(target, new CompositeMatcher().matchView(
                        activity.getWindow().getDecorView(),
                        new MatchSpec.Builder().depth(realDepth).build()));

                assertNull(new CompositeMatcher().matchView(
                                activity.getWindow().getDecorView(),
                                new MatchSpec.Builder().depth(realDepth)
                                        .viewClass(android.widget.Button.class.getName()).build()));
        });
    }

    @Test
    public void structuralMatchRequiresEveryNonEmptyFieldToAgreeUnderStrictParent() throws Exception {
        withActivity(activity -> {
                resetHostTree(activity);
                FrameLayout parent = new FrameLayout(activity);
                TextView view = new TextView(activity);
                view.setText("ok-title");
                view.setContentDescription("ok-desc");
                parent.addView(view);
                activity.mount(parent);

                MatchSpec allFields = new MatchSpec.Builder()
                        .viewClass(TextView.class.getName())
                        .text("ok-title")
                        .description("ok-desc")
                        .parentClass(FrameLayout.class.getName())
                        .matchMode(MatchMode.EXACT)
                        .build();
                assertTrue(CompositeMatcher.isStructuralMatch(view, allFields, true));

                assertFalse(CompositeMatcher.isStructuralMatch(view, allFields.toBuilder()
                        .viewClass(android.widget.Button.class.getName()).build(), true));
                assertFalse(CompositeMatcher.isStructuralMatch(view, allFields.toBuilder()
                        .text("nope").build(), true));
                assertFalse(CompositeMatcher.isStructuralMatch(view, allFields.toBuilder()
                        .description("nope").build(), true));
                assertFalse(CompositeMatcher.isStructuralMatch(view, allFields.toBuilder()
                        .parentClass(LinearLayout.class.getName()).build(), true));
                // text requires a TextView even when nothing else disagrees.
                assertFalse(CompositeMatcher.isStructuralMatch(parent, allFields.toBuilder()
                        .viewClass(null).description(null).build(), true));

                // Info-flow mode does not enforce parentClass (strictParent=false).
                assertTrue(CompositeMatcher.isStructuralMatch(view, allFields.toBuilder()
                        .parentClass(LinearLayout.class.getName()).build(), false));
        });
    }

    @Test
    public void repeatableLocatorSkipsTextAndDescriptionAcrossCards() throws Exception {
        withActivity(activity -> {
                resetHostTree(activity);
                View decor = activity.getWindow().getDecorView();
                FakeRecyclerView rv = attachFeed(activity);
                TextView firstAnchor = attachCard(activity, rv, "shared-title");
                TextView secondAnchor = attachCard(activity, rv, "shared-title");

                String[] labelPath = labelItemPath();
                // Cross-card contract: captured text/description must not gate matching.
                MatchSpec structuralOnly = new MatchSpec.Builder()
                        .repeatable(true)
                        .itemRootClass(FeedCard.class.getName())
                        .itemPath(labelPath)
                        .text("completely-wrong")
                        .description("also-wrong")
                        .build();
                List<View> hits =
                        new CompositeMatcher().matchAllViews(decor, structuralOnly);
                assertEquals(2, hits.size());
                assertSame(firstAnchor, hits.get(0));
                assertSame(secondAnchor, hits.get(1));

                // Without repeatable+itemPath the same wrong fields do gate matching.
                assertFalse(CompositeMatcher.isStructuralMatch(firstAnchor,
                        structuralOnly.toBuilder().repeatable(false).build(), false));
                assertFalse(CompositeMatcher.isStructuralMatch(firstAnchor,
                        structuralOnly.toBuilder().repeatable(false)
                                .text("shared-title").build(), false));
        });
    }

    @Test
    public void cardLevelSkipsHiddenItemRootsButElementDoesNot() throws Exception {
        withActivity(activity -> {
                resetHostTree(activity);
                View decor = activity.getWindow().getDecorView();
                FakeRecyclerView rv = attachFeed(activity);
                TextView visibleA = attachCard(activity, rv, "title-a");
                TextView visibleB = attachCard(activity, rv, "title-b");
                TextView hiddenAnchor = attachCard(activity, rv, "title-c");
                cardRootOf(hiddenAnchor).setVisibility(View.GONE);

                CompositeMatcher matcher = new CompositeMatcher();

                // ELEMENT scans every structurally identical card root, hidden included.
                List<View> elementHits = matcher.matchAllViews(decor, elementlessSpec());
                assertEquals(3, elementHits.size());

                // CARD adds a visibility gate on the card root only; the returned
                // views are the same navigation targets as ELEMENT's.
                List<View> cardHits = matcher.matchAllViews(decor, cardLevelSpec());
                assertEquals(2, cardHits.size());
                assertSame(visibleA, cardHits.get(0));
                assertSame(visibleB, cardHits.get(1));
        });
    }

    @Test
    public void gmTaggedViewsAreInvisibleToAnchorsButNotRecyclerScan() throws Exception {
        withActivity(activity -> {
                resetHostTree(activity);
                View decor = activity.getWindow().getDecorView();

                TextView tagged = new TextView(activity);
                tagged.setTag(GmConstants.TAG_GM_CMP);
                activity.mount(tagged);
                int[] taggedDepth = ViewTraversal.getViewHierarchyDepth(tagged);
                // Anchor paths gate on isVisibleView, which rejects GM components.
                assertNull(new CompositeMatcher().matchView(decor,
                        new MatchSpec.Builder().depth(taggedDepth).build()));

                // Current recycler scan performs no tag check — locked as-is so any
                // future change to this asymmetry fails loudly here.
                FakeRecyclerView rv = attachFeed(activity);
                TextView normal = attachCard(activity, rv, "keep");
                attachTaggedCard(activity, rv, "skip");
                Map<Integer, List<View>> results = new CompositeMatcher()
                        .matchAllViewsBatch(decor, Collections.singletonList(elementlessSpec()));
                assertEquals(2, results.get(0).size());
                assertSame(normal, results.get(0).get(0));
        });
    }

    @Test
    public void batchSharesTraversalAcrossRulesAndDropsIneligibleSpecs() throws Exception {
        withActivity(activity -> {
                resetHostTree(activity);
                View decor = activity.getWindow().getDecorView();
                FakeRecyclerView rv = attachFeed(activity);
                TextView firstAnchor = attachCard(activity, rv, "row-a");
                TextView secondAnchor = attachCard(activity, rv, "row-b");
                ViewGroup firstBody = (ViewGroup) firstAnchor.getParent();

                MatchSpec labelRule = elementlessSpec().toBuilder()
                        .itemPath(labelItemPath()).build();
                MatchSpec bodyRule = elementlessSpec().toBuilder()
                        .itemPath(bodyItemPath()).build();
                MatchSpec notRepeatable = new MatchSpec.Builder()
                        .text("row-a")
                        .itemRootClass(FeedCard.class.getName())
                        .itemPath(labelItemPath())
                        .build();

                Map<Integer, List<View>> results = new CompositeMatcher()
                        .matchAllViewsBatch(decor, Arrays.asList(labelRule, bodyRule,
                                notRepeatable, null));

                // Only eligible (repeatable + valid locator) specs enter the map.
                assertEquals(2, results.size());
                assertTrue(results.containsKey(0));
                assertTrue(results.containsKey(1));
                assertEquals(2, results.get(0).size());
                assertSame(firstAnchor, results.get(0).get(0));
                assertSame(secondAnchor, results.get(0).get(1));
                assertEquals(2, results.get(1).size());
                assertSame(firstBody, results.get(1).get(0));
                assertSame(secondAnchor.getParent(), results.get(1).get(1));
        });
    }

    @Test
    public void batchCapsRepeatableResultsAtMaxRepeatableResultsLimit() throws Exception {
        withActivity(activity -> {
                resetHostTree(activity);
                View decor = activity.getWindow().getDecorView();
                FakeRecyclerView rv = attachFeed(activity);
                List<TextView> anchors = new ArrayList<>();
                for (int i = 0; i <= GmConstants.MAX_REPEATABLE_RESULTS; i++) {
                    anchors.add(attachCard(activity, rv, "row-" + i));
                }

                List<View> hits = new CompositeMatcher().matchAllViewsBatch(decor,
                        Collections.singletonList(elementlessSpec())).get(0);

                assertEquals(GmConstants.MAX_REPEATABLE_RESULTS, hits.size());
                assertSame(anchors.get(0), hits.get(0));
                assertSame(anchors.get(GmConstants.MAX_REPEATABLE_RESULTS - 1),
                        hits.get(hits.size() - 1));
                assertFalse(hits.contains(anchors.get(GmConstants.MAX_REPEATABLE_RESULTS)));
        });
    }

    @Test
    public void viewTypeFilterKeepsOnlyMatchingInfoFlowRows() throws Exception {
        withActivity(activity -> {
                resetHostTree(activity);
                View decor = activity.getWindow().getDecorView();
                FakeRecyclerView rv = attachFeed(activity);
                TextView typeOneA = attachCard(activity, rv, "row-a");
                TextView typeTwo = attachCard(activity, rv, "row-b");
                TextView typeOneB = attachCard(activity, rv, "row-c");
                rv.setFakeAdapter(new FixedTypeAdapter(1, 2, 1));
                CompositeMatcher matcher = new CompositeMatcher();

                MatchSpec.Builder template = elementlessSpec().toBuilder();
                Map<Integer, List<View>> ones = matcher.matchAllViewsBatch(decor,
                        Collections.singletonList(template.viewType(1).build()));
                assertEquals(2, ones.get(0).size());
                assertSame(typeOneA, ones.get(0).get(0));
                assertSame(typeOneB, ones.get(0).get(1));

                Map<Integer, List<View>> twos = matcher.matchAllViewsBatch(decor,
                        Collections.singletonList(template.viewType(2).build()));
                assertEquals(1, twos.get(0).size());
                assertSame(typeTwo, twos.get(0).get(0));

                assertTrue(matcher.matchAllViewsBatch(decor,
                        Collections.singletonList(template.viewType(9).build()))
                        .get(0).isEmpty());

                // viewType=0 disables filtering entirely.
                Map<Integer, List<View>> unfiltered = matcher.matchAllViewsBatch(decor,
                        Collections.singletonList(template.viewType(0).build()));
                assertEquals(3, unfiltered.get(0).size());
        });
    }

    @Test
    public void recyclerCacheServesStaleTreeUntilInvalidated() throws Exception {
        withActivity(activity -> {
                resetHostTree(activity);
                View decor = activity.getWindow().getDecorView();
                CompositeMatcher matcher = new CompositeMatcher();

                FakeRecyclerView firstRv = attachFeed(activity);
                TextView initial = attachCard(activity, firstRv, "initial");
                assertEquals(1, matcher.matchAllViewsBatch(decor,
                        Collections.singletonList(elementlessSpec())).get(0).size());

                // A newly mounted RecyclerView is invisible while the cache serves
                // the snapshot taken for this decor root.
                FakeRecyclerView secondRv = attachFeed(activity);
                TextView added = attachCard(activity, secondRv, "added");
                assertEquals(1, matcher.matchAllViewsBatch(decor,
                        Collections.singletonList(elementlessSpec())).get(0).size());

                matcher.invalidateRecyclerCache();
                List<View> refreshed = matcher.matchAllViewsBatch(decor,
                        Collections.singletonList(elementlessSpec())).get(0);
                assertEquals(2, refreshed.size());
                assertSame(initial, refreshed.get(0));
                assertSame(added, refreshed.get(1));
        });
    }

    private static void withActivity(ActivityAssertion assertion) throws Exception {
        CompositeMatcherTestActivity activity = awaitActivity(10_000L);
        assertNotNull("Test host Activity was not started", activity);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                assertion.run(activity);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        Throwable throwable = failure.get();
        if (throwable instanceof Exception) throw (Exception) throwable;
        if (throwable instanceof Error) throw (Error) throwable;
    }

    private static CompositeMatcherTestActivity awaitActivity(long timeoutMillis) {
        long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<CompositeMatcherTestActivity> found = new AtomicReference<>();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Collection<Activity> resumed = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED);
                for (Activity activity : resumed) {
                    if (activity instanceof CompositeMatcherTestActivity) {
                        found.set((CompositeMatcherTestActivity) activity);
                        break;
                    }
                }
            });
            if (found.get() != null) return found.get();
            SystemClock.sleep(50L);
        }
        return null;
    }

    /**
     * 用例间共享同一 RESUMED 实例且断言依赖精确计数，
     * 每个用例开始时清空根容器以隔离前序用例遗留的视图树。
     */
    private static void resetHostTree(CompositeMatcherTestActivity activity) {
        activity.getRoot().removeAllViews();
    }

    /** 挂载一个信息流容器并返回它 */
    private static FakeRecyclerView attachFeed(CompositeMatcherTestActivity activity) {
        FakeRecyclerView rv = new FakeRecyclerView(activity);
        activity.mount(rv);
        return rv;
    }

    /**
     * 构造并挂载一张卡片：FeedCard > LinearLayout > TextView(text)。
     *
     * @return 卡片内的锚点 TextView
     */
    private static TextView attachCard(CompositeMatcherTestActivity activity,
            FakeRecyclerView rv, String text) {
        FeedCard card = new FeedCard(activity);
        LinearLayout body = new LinearLayout(activity);
        TextView anchor = new TextView(activity);
        anchor.setText(text);
        body.addView(anchor);
        card.addView(body);
        rv.addView(card);
        return anchor;
    }

    /** 由锚点上溯到所属卡片根（结构由 {@link #attachCard} 固定） */
    private static View cardRootOf(TextView anchor) {
        return (View) anchor.getParent().getParent();
    }

    private static void attachTaggedCard(CompositeMatcherTestActivity activity,
            FakeRecyclerView rv, String text) {
        cardRootOf(attachCard(activity, rv, text)).setTag(GmConstants.TAG_GM_CMP);
    }

    private static MatchSpec elementlessSpec() {
        return new MatchSpec.Builder()
                .repeatable(true)
                .itemRootClass(FeedCard.class.getName())
                .itemPath(labelItemPath())
                .build();
    }

    private static MatchSpec cardLevelSpec() {
        return elementlessSpec().toBuilder()
                .targetLevel(TargetLevel.CARD)
                .build();
    }

    private static String[] labelItemPath() {
        return new String[]{
                "0:" + LinearLayout.class.getName(),
                "0:" + TextView.class.getName()};
    }

    private static String[] bodyItemPath() {
        return new String[]{"0:" + LinearLayout.class.getName()};
    }

    /**
     * 测试替身 RecyclerView — 类名含 "RecyclerView"，命中生产代码按类名字符串识别的
     * 收集路径；提供 getAdapter()/getChildAdapterPosition(View) 等反射兼容签名。
     */
    public static final class FakeRecyclerView extends ViewGroup {

        private Object adapter;

        FakeRecyclerView(Context context) {
            super(context);
        }

        void setFakeAdapter(Object adapter) {
            this.adapter = adapter;
        }

        public Object getAdapter() {
            return adapter;
        }

        public int getChildAdapterPosition(View child) {
            return indexOfChild(child);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            // 测试替身无需真实布局
        }
    }

    /** 固定 viewType 映射的 adapter 替身，getItemViewType(int) 经反射调用 */
    public static final class FixedTypeAdapter {

        private final int[] viewTypes;

        FixedTypeAdapter(int... viewTypes) {
            this.viewTypes = viewTypes.clone();
        }

        public int getItemViewType(int position) {
            if (position >= 0 && position < viewTypes.length) {
                return viewTypes[position];
            }
            return -1;
        }
    }

    /** 信息流卡片根替身 — 提供稳定的 itemRootClass 全名 */
    public static final class FeedCard extends FrameLayout {

        FeedCard(Context context) {
            super(context);
        }
    }

    // =========================================================================
    // 非 repeatable matchView —— 双锚交叉（depth 定位 + resourceName 验证）
    // =========================================================================

    /**
     * 布局模板复用使同一 id 存在多实例：depth 指向第二个实例时，
     * 必须命中第二个而非遍历序第一个（"稳定错配首个元素"缺陷的回归锁定）。
     */
    @Test
    public void matchViewResolvesSharedResourceIdViaDepthInsteadOfFirstHit() throws Exception {
        withActivity(activity -> {
            int sharedId = com.kaisar.xposed.godmode.engine.test.R.id.test_shared_icon;

            FrameLayout firstCard = new FrameLayout(activity);
            ImageView firstIcon = new ImageView(activity);
            firstIcon.setId(sharedId);
            firstCard.addView(firstIcon);

            FrameLayout secondCard = new FrameLayout(activity);
            ImageView target = new ImageView(activity);
            target.setId(sharedId);
            secondCard.addView(target);

            activity.mount(firstCard);
            activity.mount(secondCard);

            View decor = activity.getWindow().getDecorView();
            MatchSpec spec = new MatchSpec.Builder()
                    .depth(ViewTraversal.getViewHierarchyDepth(target))
                    .viewClass(ImageView.class.getName())
                    .resourceName(activity.getResources().getResourceName(sharedId))
                    .build();

            assertSame(target, new CompositeMatcher().matchView(decor, spec));
        });
    }

    /** depth 与 res_name 双锚冲突（交叉验证失败）必须拒绝，宁缺勿错。 */
    @Test
    public void depthAnchorCrossValidatesResourceNameAndRejectsDrift() throws Exception {
        withActivity(activity -> {
            FrameLayout driftHost = new FrameLayout(activity);
            TextView wrong = new TextView(activity);
            driftHost.addView(wrong);
            activity.mount(driftHost);

            View decor = activity.getWindow().getDecorView();
            int sharedId = com.kaisar.xposed.godmode.engine.test.R.id.test_shared_icon;
            MatchSpec conflicting = new MatchSpec.Builder()
                    .depth(ViewTraversal.getViewHierarchyDepth(wrong))
                    .viewClass(TextView.class.getName())
                    .resourceName(activity.getResources().getResourceName(sharedId))
                    .build();

            assertNull(new CompositeMatcher().matchView(decor, conflicting));
        });
    }

    /** 无 depth 的旧规则保留 resourceName 单锚行为（向后兼容契约）。 */
    @Test
    public void legacyResourceOnlySpecStillResolvesFirstVisibleHit() throws Exception {
        withActivity(activity -> {
            FrameLayout host = new FrameLayout(activity);
            ImageView icon = new ImageView(activity);
            icon.setId(com.kaisar.xposed.godmode.engine.test.R.id.test_shared_icon);
            host.addView(icon);
            activity.mount(host);

            View decor = activity.getWindow().getDecorView();
            MatchSpec legacy = new MatchSpec.Builder()
                    .viewClass(ImageView.class.getName())
                    .resourceName(activity.getResources().getResourceName(
                            com.kaisar.xposed.godmode.engine.test.R.id.test_shared_icon))
                    .build();

            assertNotNull(new CompositeMatcher().matchView(decor, legacy));
        });
    }

    private interface ActivityAssertion {
        void run(CompositeMatcherTestActivity activity) throws Exception;
    }
}
