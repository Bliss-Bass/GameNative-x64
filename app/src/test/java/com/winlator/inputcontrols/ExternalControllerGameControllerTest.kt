package com.winlator.inputcontrols

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalControllerGameControllerTest {
    @Test
    fun yichipCompositeMouse_isNotGameController() {
        // MOUSE|JOYSTICK dongle with no face buttons (volume axis only).
        assertFalse(
            ExternalController.classifyGameController(
                /* isGamepad */ false,
                /* isJoystick */ true,
                /* isPointer */ true,
                /* hasControllerStickAxes */ false,
                /* hasGamepadFaceButtons */ false,
            ),
        )
    }

    @Test
    fun realGamepad_isGameController() {
        assertTrue(
            ExternalController.classifyGameController(
                true,
                true,
                false,
                true,
                true,
            ),
        )
    }

    @Test
    fun joystickOnlyWithStickAxes_isGameController() {
        assertTrue(
            ExternalController.classifyGameController(
                false,
                true,
                false,
                true,
                false,
            ),
        )
    }

    @Test
    fun mouseWithFakeJoystickClaim_stillNeedsFaceButtons() {
        assertFalse(
            ExternalController.classifyGameController(
                false,
                true,
                true,
                true,
                false,
            ),
        )
        assertTrue(
            ExternalController.classifyGameController(
                true,
                true,
                true,
                true,
                true,
            ),
        )
    }
}
