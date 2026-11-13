package com.krysta1ll.meowpuut.android.ime

import android.annotation.TargetApi
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader

/** Samples the keyboard's own backdrop through a rounded glass edge. Coordinates are in dp. */
@TargetApi(33)
internal class CandidateGlassLens {
    private val shader = RuntimeShader(SOURCE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@CandidateGlassLens.shader }

    fun configure(backdrop: Shader, glass: Box, density: Float, look: Look) {
        shader.setInputShader("backdrop", backdrop)
        shader.setFloatUniform("bounds", glass.left, glass.top, glass.width, glass.height)
        shader.setFloatUniform("density", density)
        shader.setFloatUniform("dark", if (look.dark) 1f else 0f)
        shader.setColorUniform("tint", look.tint)
        val strength = when (look.strength) {
            GlassStrength.SOFT -> 0.58f
            GlassStrength.BALANCED -> 0.8f
            GlassStrength.VIVID -> 1f
        }
        shader.setFloatUniform("strength", strength)
    }

    private companion object {
        val SOURCE = """
            uniform shader backdrop;
            uniform float4 bounds;
            uniform float density;
            uniform float dark;
            uniform float strength;
            layout(color) uniform half4 tint;

            half4 main(float2 position) {
                float2 local = position - bounds.xy - bounds.zw * 0.5;
                float radius = bounds.w * 0.5;
                float straight = max(0.0, bounds.z * 0.5 - radius);
                float2 fromAxis = local - float2(clamp(local.x, -straight, straight), 0.0);
                float distance = length(fromAxis);
                float2 normal = fromAxis / max(distance, 0.001);
                float depth = max(0.0, radius - distance);
                float bevel = 1.0 - smoothstep(0.0, min(11.0, radius * 0.65), depth);

                // The flat center transmits directly. Only the curved rim bends the rays.
                float2 ray = position - normal * (bevel * bevel * 12.0 * strength);
                half4 transmitted = backdrop.eval(ray * density);
                half3 ground = transmitted.rgb / max(transmitted.a, 0.001);
                half3 veil = mix(half3(1.0, 0.99, 0.99), half3(0.11, 0.095, 0.12), dark);
                half3 color = mix(ground, veil, 0.18 + 0.12 * (1.0 - strength));
                color = mix(color, tint.rgb, 0.055);

                float facingLight = clamp(dot(normal, float2(-0.48, -0.88)), 0.0, 1.0);
                float rim = 1.0 - smoothstep(0.35, 1.6, depth);
                float reflected = rim * (0.16 + facingLight * 0.62) + bevel * facingLight * 0.12;
                float facingAway = clamp(dot(normal, float2(0.38, 0.92)), 0.0, 1.0);
                color = mix(color, tint.rgb, bevel * facingAway * mix(0.23, 0.30, dark));
                color = mix(color, half3(1.0), reflected);
                return half4(color, 1.0);
            }
        """.trimIndent()
    }
}
