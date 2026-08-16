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
