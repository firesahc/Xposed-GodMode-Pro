package com.kaisar.xposed.godmode.engine.rule;

/** A remove rule effect. Only visibility is a runtime capability. */
public final class RemoveEffect extends RuleEffect {

    RemoveEffect(WireValues wireValues) {
        super(wireValues);
    }

    public static RemoveEffect of(int visibility) {
        return new RemoveEffect(new WireValues.Builder()
                .visibility(visibility)
                .build());
    }

    @Override
    public Kind getKind() {
        return Kind.REMOVE;
    }

    public int getVisibility() {
        return toWireValues().getVisibility();
    }

    /**
     * 运行时效果相等 — visibility 是 REMOVE 效果唯一的运行时能力，故仅比较它；
     * ruleTag 同样不参与（同 {@link ModifyEffect#equals} 的语义说明）。
     */
    @Override
    public boolean equals(Object object) {
        return this == object || object instanceof RemoveEffect
                && getVisibility() == ((RemoveEffect) object).getVisibility();
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(getVisibility());
    }
}
