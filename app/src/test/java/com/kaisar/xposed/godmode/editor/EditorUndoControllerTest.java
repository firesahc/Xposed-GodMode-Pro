package com.kaisar.xposed.godmode.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.kaisar.xposed.godmode.ipc.contract.UndoStateParcel;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public class EditorUndoControllerTest {

    @Test
    public void storesOnlyAuthoritativeProjectionAndDisablesWhileUndoing() {
        EditorUndoController controller = new EditorUndoController();
        AtomicBoolean available = new AtomicBoolean();
        controller.setListener(available::set);
        controller.bindPackage("target.pkg");

        UndoStateParcel state = state("target.pkg", 2, 8L);
        project(controller, state);
        assertSame(state, controller.getProjection());
        assertTrue(available.get());

        EditorUndoController.UndoAttempt attempt = controller.beginUndo();
        assertSame(state, attempt.expected);
        assertFalse(available.get());
        assertTrue(controller.isOperationInFlight());

        UndoStateParcel empty = state("target.pkg", 0, 0L);
        assertTrue(controller.completeUndo(attempt.scopeGeneration, empty));
        assertSame(empty, controller.getProjection());
        assertFalse(controller.isUndoAvailable());
    }

    @Test
    public void failedForwardMutationRetainsPriorProjection() {
        EditorUndoController controller = new EditorUndoController();
        controller.bindPackage("target.pkg");
        UndoStateParcel prior = state("target.pkg", 1, 7L);
        project(controller, prior);

        long scope = controller.beginForwardMutation();
        assertTrue(scope != EditorUndoController.INVALID_SCOPE);
        assertFalse(controller.isUndoAvailable());
        assertTrue(controller.failForwardMutation(scope));

        assertSame(prior, controller.getProjection());
        assertTrue(controller.isUndoAvailable());
    }

    @Test
    public void packageChangeClearsProjectionAndRejectsForeignState() {
        EditorUndoController controller = new EditorUndoController();
        controller.bindPackage("first.pkg");
        project(controller, state("first.pkg", 1, 3L));

        controller.bindPackage("second.pkg");
        assertNull(controller.getProjection());
        assertFalse(controller.isUndoAvailable());

        long refreshScope = controller.beginRefresh();
        controller.completeRefresh(refreshScope, state("first.pkg", 4, 9L));
        assertNull(controller.getProjection());
        assertFalse(controller.isUndoAvailable());
    }

    @Test
    public void allowsOnlyOneInFlightOperation() {
        EditorUndoController controller = new EditorUndoController();
        controller.bindPackage("target.pkg");
        project(controller, state("target.pkg", 1, 1L));

        long scope = controller.beginForwardMutation();
        assertTrue(scope != EditorUndoController.INVALID_SCOPE);
        assertEquals(EditorUndoController.INVALID_SCOPE, controller.beginForwardMutation());
        assertNull(controller.beginUndo());
        assertEquals(EditorUndoController.INVALID_SCOPE, controller.beginRefresh());
        controller.completeForwardMutation(scope, state("target.pkg", 2, 2L));

        assertEquals(2, controller.getProjection().depth);
        assertTrue(controller.isUndoAvailable());
    }

    @Test
    public void failedRefreshRetainsProjectionButDoesNotAuthorizeUndoContinuation() {
        EditorUndoController controller = new EditorUndoController();
        controller.bindPackage("target.pkg");
        UndoStateParcel prior = state("target.pkg", 1, 1L);
        project(controller, prior);

        long scope = controller.beginRefresh();
        assertTrue(scope != EditorUndoController.INVALID_SCOPE);
        assertFalse(controller.completeRefresh(scope, null));

        assertSame(prior, controller.getProjection());
        assertTrue(controller.isUndoAvailable());
    }

    @Test
    public void transientRefreshRetainsProjectionWithoutReportingFreshState() {
        EditorUndoController controller = new EditorUndoController();
        controller.bindPackage("target.pkg");
        UndoStateParcel prior = state("target.pkg", 1, 1L);
        project(controller, prior);

        long scope = controller.beginRefresh();
        UndoStateParcel busy = new UndoStateParcel(2, "target.pkg", 11L,
                0L, 0, 0L, null, "busy");
        assertFalse(controller.completeRefresh(scope, busy));

        assertSame(prior, controller.getProjection());
        assertTrue(controller.isUndoAvailable());
    }

    @Test
    public void lateRefreshCannotPolluteSamePackageAfterEditRestart() {
        EditorUndoController controller = new EditorUndoController();
        controller.bindPackage("target.pkg");
        long oldScope = controller.beginRefresh();

        restartSamePackage(controller);
        UndoStateParcel current = state("target.pkg", 1, 20L);
        project(controller, current);

        assertFalse(controller.completeRefresh(oldScope, state("target.pkg", 5, 99L)));
        assertSame(current, controller.getProjection());
    }

    @Test
    public void lateForwardCompletionCannotPolluteSamePackageAfterEditRestart() {
        EditorUndoController controller = new EditorUndoController();
        controller.bindPackage("target.pkg");
        project(controller, state("target.pkg", 1, 10L));
        long oldScope = controller.beginForwardMutation();

        restartSamePackage(controller);
        UndoStateParcel current = state("target.pkg", 2, 20L);
        project(controller, current);

        assertFalse(controller.completeForwardMutation(oldScope,
                state("target.pkg", 6, 99L)));
        assertSame(current, controller.getProjection());
    }

    @Test
    public void lateUndoCompletionCannotPolluteSamePackageAfterEditRestart() {
        EditorUndoController controller = new EditorUndoController();
        controller.bindPackage("target.pkg");
        project(controller, state("target.pkg", 1, 10L));
        EditorUndoController.UndoAttempt oldAttempt = controller.beginUndo();

        restartSamePackage(controller);
        UndoStateParcel current = state("target.pkg", 2, 20L);
        project(controller, current);

        assertFalse(controller.completeUndo(oldAttempt.scopeGeneration,
                state("target.pkg", 0, 0L)));
        assertSame(current, controller.getProjection());
    }

    private static void project(EditorUndoController controller, UndoStateParcel state) {
        long scope = controller.beginRefresh();
        assertTrue(scope != EditorUndoController.INVALID_SCOPE);
        assertTrue(controller.completeRefresh(scope, state));
    }

    private static void restartSamePackage(EditorUndoController controller) {
        controller.bindPackage(null);
        controller.bindPackage("target.pkg");
    }

    private static UndoStateParcel state(String packageName, int depth, long topSequence) {
        return new UndoStateParcel(0, packageName, 11L, 12L, depth, topSequence,
                depth == 0 ? null : "source", null);
    }
}
