package com.aida0710.chattranslator

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier
import kotlin.test.Test
import kotlin.test.assertEquals

class KeybindsTest {
    private val category = KeyMapping.Category(Identifier.fromNamespaceAndPath("chattranslator_test", "main"))

    @Test
    fun boundKeyFollowsTheCurrentAssignment() {
        val mapping = KeyMapping("key.chattranslator_test.hold", InputConstants.KEY_J, category)
        assertEquals(InputConstants.Type.KEYBOARD, Keybinds.boundKey(mapping).type)
        assertEquals(InputConstants.KEY_J, Keybinds.boundKey(mapping).value)

        mapping.setKey(InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_K))
        assertEquals(InputConstants.KEY_K, Keybinds.boundKey(mapping).value)

        mapping.setKey(InputConstants.Type.MOUSE.getOrCreate(1))
        assertEquals(InputConstants.Type.MOUSE, Keybinds.boundKey(mapping).type)
    }
}
