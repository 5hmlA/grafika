/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.Toast;
import androidx.annotation.NonNull;
import java.io.IOException;

/**
 * More or less straight out of TextureView's doc.
 * <p>
 * TODO: add options for different display sizes, frame rates, camera selection, etc.
 * 
 * 📷 实时摄像头预览Activity
 * 💡 简单的TextureView摄像头预览示例
 */
public class LiveCameraActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = MainActivity.TAG;

    private Camera mCamera;                    // 📷 摄像头
    private SurfaceTexture mSurfaceTexture;    // 🖼️ Surface纹理

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 📞 调用父类onCreate（为什么：Activity生命周期要求）
        // 💡 作用：执行Activity的初始化逻辑
        // ⏰ 使用时机：Activity创建时首先调用
        super.onCreate(savedInstanceState);

        // 🎨 创建TextureView（为什么：需要显示摄像头预览画面）
        // 💡 作用：提供可显示摄像头画面的视图组件
        // ⏰ 使用时机：Activity创建时创建视图
        // 💡 参数：this表示当前Activity作为上下文
        TextureView textureView = new TextureView(this);

        // 📋 设置SurfaceTexture监听器（为什么：需要在SurfaceTexture可用时启动摄像头）
        // 💡 作用：监听TextureView的SurfaceTexture生命周期
        // ⏰ 使用时机：TextureView创建后立即设置
        // 💡 this表示当前Activity实现的SurfaceTextureListener接口
        textureView.setSurfaceTextureListener(this);

        // 🎨 设置内容视图（为什么：将TextureView作为Activity的根视图）
        // 💡 作用：将TextureView设置为当前Activity显示的内容
        // ⏰ 使用时机：监听器设置完成后
        setContentView(textureView);
    }

    /**
     * 🖼️ SurfaceTexture可用时调用
     * 💡 TextureView初始化完成后触发
     * 💡 作用：检查摄像头权限，有权限则开始预览
     *
     * @param surface SurfaceTexture对象（用于摄像头预览输出）
     * @param width SurfaceTexture宽度（像素）
     * @param height SurfaceTexture高度（像素）
     */
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        // 🖼️ 保存SurfaceTexture引用
        // 💡 作用：后续startPreview时需要将摄像头输出绑定到此SurfaceTexture
        // 💡 时机：回调触发时立即保存
        mSurfaceTexture = surface;

        // 🔍 检查是否已获得摄像头权限
        // 💡 作用：Android 6.0+需要运行时权限，必须先检查
        // 💡 时机：SurfaceTexture可用后，预览前检查
        if (!PermissionHelper.hasCameraPermission(this)) {
            // 📋 请求摄像头权限
            // 💡 作用：弹出系统权限请求对话框
            // 💡 参数：Activity引用、是否为必需权限（true会直接拒绝无权限）
            PermissionHelper.requestCameraPermission(this, false);
        } else {
            // ▶️ 已有权限，直接开始预览
            // 💡 作用：初始化摄像头并开始实时预览
            startPreview();
        }
    }

    /**
     * 🖼️ SurfaceTexture尺寸变化时调用
     * 💡 当TextureView的SurfaceTexture尺寸发生改变时触发
     * 💡 作用：Camera API会自动处理尺寸变化，此处不需要特殊处理
     *
     * @param surface SurfaceTexture对象（摄像头预览输出目标）
     * @param width 新的SurfaceTexture宽度（像素）
     * @param height 新的SurfaceTexture高度（像素）
     */
    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // 📷 忽略尺寸变化（为什么：Camera API会自动处理预览尺寸）
        // 💡 作用：Camera会根据SurfaceTexture大小自动调整预览输出
        // ⏰ 使用时机：SurfaceTexture尺寸改变时调用
        // 💡 此处留空，不需要特殊处理
        // 💡 原因：Camera硬件会自动适配输出尺寸，无需手动调整
    }

    /**
     * 🖼️ SurfaceTexture销毁时调用
     * 💡 当TextureView的SurfaceTexture即将被销毁时触发
     * 💡 作用：停止摄像头预览并释放硬件资源
     *
     * @param surface 即将销毁的SurfaceTexture对象
     * @return true表示已处理销毁（调用者不需要额外处理）
     */
    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        // 🛑 停止摄像头预览（为什么：SurfaceTexture销毁时需要停止预览）
        // 💡 作用：停止摄像头捕获画面，避免输出到已销毁的Surface
        // ⏰ 使用时机：SurfaceTexture即将销毁时首先调用
        mCamera.stopPreview();

        // 🗑️ 释放摄像头资源（为什么：避免资源泄漏，其他应用无法使用摄像头）
        // 💡 作用：释放Camera实例占用的硬件资源
        // ⏰ 使用时机：停止预览后立即释放
        mCamera.release();

        // ✅ 返回true（为什么：表示已处理SurfaceTexture销毁）
        // 💡 作用：告知系统SurfaceTexture已清理，无需额外处理
        // ⏰ 使用时机：资源释放后返回
        return true;
    }

    /**
     * 🖼️ SurfaceTexture更新时调用（每帧触发）
     * 💡 摄像头每捕获一帧画面都会触发此回调
     * 💡 作用：可用于实时处理摄像头帧数据（如图像分析、滤镜处理等）
     *
     * @param surface 正在更新的SurfaceTexture对象
     */
    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // 🎬 每帧更新时调用（为什么：可以在这里处理每帧数据）
        // 💡 作用：摄像头每捕获一帧画面都会触发此回调
        // ⏰ 使用时机：每帧渲染完成后调用
        // 💡 目前留空，如需处理帧数据可在此添加逻辑
        // 💡 示例：可用surface.getTransformMatrix()获取变换矩阵
        // 💡 示例：可用Bitmap处理帧图像数据
    }

    /**
     * 📋 权限请求结果回调
     * 💡 用户在权限对话框中做出选择后调用
     * 💡 作用：根据用户授权结果决定是否开始预览
     *
     * @param requestCode 请求码（用于识别是哪个权限请求）
     * @param permissions 请求的权限数组
     * @param grantResults 授权结果数组（PERMISSION_GRANTED或PERMISSION_DENIED）
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        // 📞 调用父类处理（通常为空实现，但保持规范）
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // 🔍 再次检查是否已获得摄像头权限
        // 💡 作用：用户可能拒绝了权限，需要确认最终状态
        // 💡 时机：权限对话框关闭后立即检查
        if (!PermissionHelper.hasCameraPermission(this)) {
            // 💬 显示提示：需要摄像头权限
            // 💡 作用：告知用户摄像头功能不可用的原因
            Toast.makeText(this, "Camera permission is needed", Toast.LENGTH_LONG).show();

            // ⚙️ 打开系统权限设置页面
            // 💡 作用：引导用户手动授予权限
            PermissionHelper.launchPermissionSettings(this);

            // 🚪 关闭Activity
            // 💡 作用：没有权限无法继续，直接退出
            finish();
        } else {
            // ▶️ 权限已授予，开始预览
            // 💡 作用：初始化摄像头并开始实时预览
            startPreview();
        }
    }

    /**
     * 📷 开始摄像头预览
     * 💡 打开默认摄像头，设置预览输出和显示方向，启动预览
     * 💡 作用：实现摄像头实时画面显示
     */
    private void startPreview() {
        // 📷 打开默认摄像头（通常是后置摄像头）
        // 💡 作用：获取Camera实例，用于控制摄像头硬件
        // 💡 时机：权限确认后，预览前调用
        mCamera = Camera.open();

        // 🔍 检查摄像头是否可用
        // 💡 作用：防止摄像头被其他应用占用或硬件故障
        // 💡 时机：Camera.open()后立即检查
        if (mCamera == null) {
            // ❌ 摄像头不可用，抛出运行时异常
            // 💡 作用：快速失败，避免后续空指针错误
            throw new RuntimeException("Default camera not available");
        }

        try {
            // 🖼️ 设置摄像头预览输出到SurfaceTexture
            // 💡 作用：将摄像头捕获的画面输出到TextureView
            // 💡 参数：之前保存的mSurfaceTexture
            mCamera.setPreviewTexture(mSurfaceTexture);

            // 📱 获取默认显示设备
            // 💡 作用：获取屏幕信息，用于计算摄像头旋转角度
            // 💡 时机：设置预览输出后，启动预览前
            Display display = ((WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

            // 🔍 检查屏幕旋转状态为竖屏（0度）
            // 💡 作用：竖屏时摄像头需要旋转90度才能正确显示
            // 💡 原因：摄像头传感器通常是横向安装的
            if(display.getRotation() == Surface.ROTATION_0) {
                // 🔄 设置显示方向为90度（顺时针旋转）
                // 💡 作用：补偿摄像头传感器的方向差异
                mCamera.setDisplayOrientation(90);
            }

            // 🔍 检查屏幕旋转状态为反向竖屏（270度）
            // 💡 作用：反向竖屏时需要旋转180度
            if(display.getRotation() == Surface.ROTATION_270) {
                // 🔄 设置显示方向为180度
                mCamera.setDisplayOrientation(180);
            }

            // ▶️ 启动摄像头预览
            // 💡 作用：开始捕获画面并输出到SurfaceTexture
            // 💡 时机：所有配置完成后最后调用
            mCamera.startPreview();
        } catch (IOException ioe) {
            // ❌ 捕获IO异常（预览设置失败）
            // 💡 作用：记录错误信息，避免应用崩溃
            Log.e(TAG,"Exception starting preview", ioe);
        }
    }
}
