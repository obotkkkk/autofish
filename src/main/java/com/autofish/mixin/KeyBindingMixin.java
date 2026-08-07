package com.autofish.mixin;

import com.autofish.AutoFishMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyBinding.class)
public class KeyBindingMixin {
    @Inject(method = "isPressed", at = @At("HEAD"), cancellable = true)
    private void onIsPressed(CallbackInfoReturnable<Boolean> cir) {
        KeyBinding self = (KeyBinding) (Object) this;
        MinecraftClient client = MinecraftClient.getInstance();
        
        if (client.options != null && self == client.options.sneakKey) {
            if (AutoFishMod.enabled && AutoFishMod.shouldHoldShift) {
                cir.setReturnValue(true);
            }
        }
    }
}
