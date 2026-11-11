package io.github.sherifshabans.mushaf

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import kotlin.math.max

/**
 * API 33+ fragment shader for the same cylinder as [sampleCylinder].
 * Loaded by reflection so API 21 never resolves [RuntimeShader].
 * Robolectric usually cannot compile AGSL; the mesh path is the fallback
 * and the path the screenshot tests draw.
 */
internal object MushafCurlAgsl {
    fun draw(
        canvas: Canvas,
        front: Bitmap,
        back: Bitmap,
        versoIsDistinct: Boolean,
        width: Float,
        height: Float,
        cylinder: CurlCylinder,
        clipLeft: Float,
        clipTop: Float,
        clipRight: Float,
        clipBottom: Float,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val shader = RuntimeShader(PAGE_CURL_AGSL)
        shader.setFloatUniform("curlPos", cylinder.posX, cylinder.posY)
        shader.setFloatUniform("curlDir", cylinder.dirX, cylinder.dirY)
        shader.setFloatUniform("axis", cylinder.axisX, cylinder.axisY)
        shader.setFloatUniform("radius", cylinder.radius)
        shader.setFloatUniform("pageSize", width, height)
        shader.setFloatUniform("texScale", front.width / width, front.height / height)
        shader.setFloatUniform("cam", width * 0.5f, height * 0.46f, max(width, height) * 2.15f)
        shader.setFloatUniform("versoDistinct", if (versoIsDistinct) 1f else 0f)
        shader.setInputShader("page", BitmapShader(front, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("backPage", BitmapShader(back, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            isFilterBitmap = true
            this.shader = shader
        }
        canvas.save()
        canvas.clipRect(
            clipLeft - cylinder.radius,
            clipTop - cylinder.radius,
            clipRight + cylinder.radius,
            clipBottom + cylinder.radius,
        )
        canvas.drawRect(clipLeft, clipTop, clipRight, clipBottom, paint)
        canvas.restore()
        return true
    }
}

private const val PAGE_CURL_AGSL = """
uniform shader page;
uniform shader backPage;
uniform float2 curlPos;
uniform float2 curlDir;
uniform float2 axis;
uniform float radius;
uniform float2 pageSize;
uniform float2 texScale;
uniform float3 cam;
uniform float versoDistinct;

const float PI = 3.14159265;

float2 project(float2 xy, float z) {
    float f = cam.z / max(cam.z - z, 1.0);
    return float2(cam.x, cam.y) + (xy - float2(cam.x, cam.y)) * f;
}

float2 unproject(float2 screen, float z) {
    float f = cam.z / max(cam.z - z, 1.0);
    return float2(cam.x, cam.y) + (screen - float2(cam.x, cam.y)) / f;
}

bool inside(float2 p) {
    return p.x >= -2.0 && p.y >= -2.0 && p.x <= pageSize.x + 2.0 && p.y <= pageSize.y + 2.0;
}

half4 shade(half4 tex, float theta, float isBack) {
    float ridge = exp(-pow(theta - PI * 0.5, 2.0) / 0.05);
    float nz = cos(theta);
    float diffuse = isBack > 0.5
        ? 0.50 + 0.28 * max(-nz, 0.0)
        : 0.28 + 0.72 * max(nz, 0.0);
    float spec = ridge * (isBack > 0.5 ? 0.18 : 0.55);
    float light = clamp(diffuse + spec, 0.0, 1.0);
    if (isBack < 0.5 && theta < 0.55) {
        light *= 0.62 + 0.38 * (theta / 0.55);
    }
    half3 tint = isBack > 0.5 ? half3(0.95, 0.88, 0.76) : half3(1.0);
    return half4(tex.rgb * half(light) * tint, 1.0);
}

half4 samplePage(float2 paper, float isBack) {
    if (isBack > 0.5 && versoDistinct > 0.5) {
        float2 uv = float2((pageSize.x - paper.x) * texScale.x, paper.y * texScale.y);
        return shade(backPage.eval(uv), thetaOf(isBack), isBack);
    }
    return shade(page.eval(paper * texScale), thetaOf(isBack), isBack);
}

float thetaOf(float isBack) {
    return isBack > 0.5 ? PI : 0.0;
}

void consider(inout float bestErr, inout half4 color, float2 frag, float2 world, float z, float2 paper, float theta, float isBack) {
    if (!inside(paper)) return;
    float err = distance(project(world, z), frag);
    if (err < bestErr) {
        bestErr = err;
        if (isBack < 0.5) {
            color = shade(page.eval(paper * texScale), theta, 0.0);
        } else {
            color = samplePage(paper, 1.0);
        }
    }
}

half4 main(float2 frag) {
    float bestErr = 1e9;
    half4 color = half4(0.0);
    float arc = PI * radius;
    for (int pass = 0; pass < 3; pass++) {
        float zTry = pass == 0 ? 0.0 : (pass == 1 ? radius : 2.0 * radius);
        float2 world = unproject(frag, zTry);
        float2 rel = world - curlPos;
        float along = dot(rel, curlDir);
        float axisC = dot(rel, axis);
        if (along <= 0.0) {
            consider(bestErr, color, frag, world, 0.0, world, 0.0, 0.0);
            float extra = -along;
            float paperDist = arc + extra;
            float z = (2.0 * radius) * exp(-extra / (radius * 0.55));
            float2 paper = curlPos + curlDir * paperDist + axis * axisC;
            float2 w = curlPos + curlDir * (-extra) + axis * axisC;
            consider(bestErr, color, frag, w, z, paper, PI, 1.0);
        }
        if (along >= 0.0 && along <= radius) {
            float theta1 = asin(clamp(along / radius, 0.0, 1.0));
            float theta2 = PI - theta1;
            float z1 = radius * (1.0 - cos(theta1));
            float z2 = radius * (1.0 - cos(theta2));
            float2 paper1 = curlPos + curlDir * (theta1 * radius) + axis * axisC;
            float2 paper2 = curlPos + curlDir * (theta2 * radius) + axis * axisC;
            float2 w = curlPos + curlDir * along + axis * axisC;
            consider(bestErr, color, frag, w, z1, paper1, theta1, 0.0);
            consider(bestErr, color, frag, w, z2, paper2, theta2, 1.0);
        }
    }
    if (bestErr > 3.0) return half4(0.0);
    return color;
}
"""
