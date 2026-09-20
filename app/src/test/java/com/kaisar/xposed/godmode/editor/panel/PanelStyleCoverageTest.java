package com.kaisar.xposed.godmode.editor.panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.kaisar.xposed.godmode.R;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面板样式契约覆盖测试 — 布局新增可样式化 id 但忘记回填映射时失败。
 * <p>
 * 布局只承载结构（background/src 已 @null 化），所有模块视觉必须出现在
 * 回填映射中；本测试比对“布局全部 id”与“映射覆盖 id＋结构免检 id”，
 * 防止加按钮/改名后漏回填。Ripple 多实例不等无法在 JVM 断言
 *（需 View 实例），由“逐 View 独立 newDrawable”的构造方式保证，见回填体注释。
 */
public final class PanelStyleCoverageTest {

    /** 布局解析失败时跳过而非失败（IDE 单测工作区与 Gradle 可能不同）。 */
    private static File findResDir() {
        String[] candidates = {
                "src/main/res",
                "app/src/main/res",
                "../app/src/main/res",
        };
        for (String candidate : candidates) {
            File dir = new File(candidate);
            if (new File(dir, "layout/panel_node_selector.xml").isFile()) return dir;
        }
        return null;
    }

    private static final Pattern ID_PATTERN = Pattern.compile("@\\+id/([A-Za-z0-9_]+)");

    private static Set<String> layoutIds(File resDir, String... relativePaths) throws Exception {
        Set<String> ids = new HashSet<>();
        for (String relative : relativePaths) {
            String text = new String(Files.readAllBytes(new File(resDir, relative).toPath()), "UTF-8");
            Matcher matcher = ID_PATTERN.matcher(text);
            while (matcher.find()) ids.add(matcher.group(1));
        }
        return ids;
    }

    /** R.id 字段名 → int 值（编译期常量，反射读取）。 */
    private static Map<String, Integer> rIdTable() throws Exception {
        Map<String, Integer> table = new HashMap<>();
        for (Field field : R.id.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                table.put(field.getName(), field.getInt(null));
            }
        }
        return table;
    }

    private static Set<Integer> resolveAll(Map<String, Integer> table, Set<String> names) {
        Set<Integer> ids = new HashSet<>();
        for (String name : names) {
            Integer value = table.get(name);
            assertTrue("R.id missing for layout id: " + name, value != null && value != 0);
            ids.add(value);
        }
        return ids;
    }

    @Test
    public void nodeSelector_mapsCoverEveryStylableView() throws Exception {
        File resDir = findResDir();
        assumeTrue("panel layouts not visible from unit-test working dir", resDir != null);
        Map<String, Integer> table = rIdTable();
        Set<String> layoutNames = layoutIds(resDir,
                "layout/panel_node_selector.xml", "layout-land/panel_node_selector.xml");

        Set<Integer> mapped = new HashSet<>();
        for (int id : NodeSelectorPanel.RIPPLE_VIEW_IDS) mapped.add(id);
        for (int[] entry : NodeSelectorPanel.SRC_BACKFILLS) mapped.add(entry[0]);
        for (int[] entry : NodeSelectorPanel.TEXT_BACKFILLS) mapped.add(entry[0]);
        for (int id : NodeSelectorPanel.HOST_FRAMEWORK_SRC_IDS) mapped.add(id);

        // 结构性容器/开关框/滑条：无模块视觉，不在回填映射内是预期的。
        Set<String> structuralNames = new HashSet<>();
        structuralNames.add("top_content");
        structuralNames.add("selection_group");
        structuralNames.add("action_group");
        structuralNames.add("remove_menu");
        structuralNames.add("modify_menu");
        structuralNames.add("slider");
        structuralNames.add("info_flow_mode_toggle");
        structuralNames.add("remove_mode_toggle");
        structuralNames.add("modify_mode_toggle");
        Set<Integer> structural = resolveAll(table, structuralNames);

        Set<Integer> layoutIds = resolveAll(table, layoutNames);
        Set<Integer> union = new HashSet<>(mapped);
        union.addAll(structural);
        assertEquals("layout ids without style coverage: " + difference(layoutIds, union),
                layoutIds, union);
        Set<Integer> overlap = new HashSet<>(mapped);
        overlap.retainAll(structural);
        assertTrue("ids both mapped and structural: " + overlap, overlap.isEmpty());
    }

    @Test
    public void modifyPanel_mapsCoverEveryStylableView() throws Exception {
        File resDir = findResDir();
        assumeTrue("panel layouts not visible from unit-test working dir", resDir != null);
        Map<String, Integer> table = rIdTable();
        Set<String> layoutNames = layoutIds(resDir, "layout/panel_modify.xml");

        Set<Integer> mapped = new HashSet<>();
        for (int[] entry : PropertyEditorPanel.TEXT_BACKFILLS) mapped.add(entry[0]);

        // 输入框/滑条/分区/字面符号按钮：无模块视觉需求，不在回填映射内是预期的。
        String[] structuralNames = {
                "mod_width_seek", "mod_width_text",
                "mod_height_seek", "mod_height_text",
                "mod_alpha_seek", "mod_alpha_text",
                "mod_text_section", "mod_text_input",
                "mod_image_section",
                "mod_pos_left", "mod_pos_up", "mod_pos_down", "mod_pos_right",
        };
        Set<Integer> structural = new HashSet<>();
        for (String name : structuralNames) {
            Integer value = table.get(name);
            assertTrue("R.id missing for layout id: " + name, value != null && value != 0);
            structural.add(value);
        }

        Set<Integer> layoutIds = resolveAll(table, layoutNames);
        Set<Integer> union = new HashSet<>(mapped);
        union.addAll(structural);
        assertEquals("layout ids without style coverage: " + difference(layoutIds, union),
                layoutIds, union);
    }

    @Test
    public void missingTextDetectsEmptyAndAtIdPlaceholders() {
        assertTrue(NodeSelectorPanel.isMissingText(""));
        assertTrue(NodeSelectorPanel.isMissingText("@-1793982322"));
        assertTrue(NodeSelectorPanel.isMissingText("@123"));
        assertTrue(PropertyEditorPanel.isMissingText(""));
        assertTrue(PropertyEditorPanel.isMissingText("@-1793982323"));
        assertFalse(NodeSelectorPanel.isMissingText("移除模式"));
        assertFalse(NodeSelectorPanel.isMissingText("信息流·关"));
        assertFalse(PropertyEditorPanel.isMissingText("修改模式"));
        assertFalse(NodeSelectorPanel.isMissingText("@abc"));
        assertFalse(NodeSelectorPanel.isMissingText("@"));
        assertFalse(PropertyEditorPanel.isMissingText("@12x"));
    }

    private static Set<Integer> difference(Set<Integer> left, Set<Integer> right) {
        Set<Integer> missing = new HashSet<>(left);
        missing.removeAll(right);
        return missing;
    }
}
