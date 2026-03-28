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

package com.android.grafika.gles;

import android.graphics.SurfaceTexture;
import android.view.Surface;

/**
 * Recordable EGL window surface.
 * <p>
 * It's good practice to explicitly release() the surface, preferably from a "finally" block.
 * 
 * 🖼️ 可录制的EGL窗口Surface
 * 💡 建议在finally块中显式调用release()
 */
public class WindowSurface extends EglSurfaceBase {
    private Surface mSurface;           // 🖼️ Android Surface对象
    private boolean mReleaseSurface;    // 🗑️ 是否在release时释放Surface

    /**
     * Associates an EGL surface with the native window surface.
     * <p>
     * Set releaseSurface to true if you want the Surface to be released when release() is
     * called.  This is convenient, but can interfere with framework classes that expect to
     * manage the Surface themselves.
     * 
     * 🔧 将EGL Surface与原生窗口Surface关联
     * @param releaseSurface 是否在release时释放Surface
     */
    public WindowSurface(EglCore eglCore, Surface surface, boolean releaseSurface) {
        super(eglCore);
        createWindowSurface(surface);
        mSurface = surface;
        mReleaseSurface = releaseSurface;
    }

    /**
     * Associates an EGL surface with the SurfaceTexture.
     * 
     * 🔧 将EGL Surface与SurfaceTexture关联
     */
    public WindowSurface(EglCore eglCore, SurfaceTexture surfaceTexture) {
        super(eglCore);
        createWindowSurface(surfaceTexture);
    }

    /**
     * Releases any resources associated with the EGL surface.
     *
     * 🗑️ 释放EGL Surface相关资源（资源清理方法！）
     * 💡 释放EGL层面的Surface资源，可选择是否同时释放Android Surface
     * 📌 使用时机：不再需要此Surface时调用，建议在finally块中调用
     */
    public void release() {
        // 🗑️ releaseEglSurface：释放EGL Surface资源（调用父类方法）
        // 💡 作用：清理EGL层面的Surface资源（eglDestroySurface等）
        // 💡 使用时机：必须先释放EGL Surface，再释放Android Surface
        releaseEglSurface();
        // 🔍 mSurface：Android Surface对象（android.view.Surface）
        // 💡 为什么检查：避免对已释放或空引用调用release()导致空指针异常
        // 💡 使用时机：释放Surface前必须检查是否为null
        if (mSurface != null) {
            // 🔍 mReleaseSurface：是否由本类负责释放Surface的标志（boolean）
            // 💡 为什么定义：某些框架类（如MediaCodec）需要自己管理Surface生命周期
            // 💡 作用：决定release()时是否同时释放Android Surface
            // 💡 使用时机：构造时设置，release()时判断
            if (mReleaseSurface) {
                // 🗑️ mSurface.release()：释放Android Surface资源
                // 💡 作用：通知系统此Surface不再使用，释放底层资源
                // 💡 仅当mReleaseSurface为true时调用（本类拥有Surface所有权）
                mSurface.release();
            }
            // 🗑️ mSurface = null：清空Surface引用
            // 💡 为什么清空：帮助GC回收，避免悬空引用
            // 💡 作用：标记Surface已释放，后续访问会触发空指针异常（快速失败）
            // 💡 使用时机：无论是否调用mSurface.release()，都要清空引用
            mSurface = null;
        }
    }

    /**
     * Recreate the EGLSurface, using the new EglBase.
     * 
     * 🔄 使用新的EglCore重建EGLSurface
     */
    public void recreate(EglCore newEglCore) {
        // ⚠️ mSurface检查：验证是否有关联的Android Surface对象
        // 💡 为什么检查：recreate只支持Surface重建，不支持SurfaceTexture
        // 💡 作用：防止对SurfaceTexture创建的WindowSurface调用此方法
        // 💡 使用时机：在重建EGL Surface之前进行前置校验
        if (mSurface == null) {
            throw new RuntimeException("not yet implemented for SurfaceTexture");
        }
        // 🔄 mEglCore：更新EGL核心对象引用（EglCore类型）
        // 💡 为什么赋值：重建Surface需要使用新的EGL上下文和配置
        // 💡 作用：将后续操作绑定到新的EGL上下文上
        // 💡 使用时机：在EGL上下文丢失或需要切换上下文时使用
        mEglCore = newEglCore;
        // 🖼️ createWindowSurface：使用现有Surface创建新的EGL窗口Surface
        // 💡 为什么调用：旧的EGL Surface可能已失效，需要用新上下文重新创建
        // 💡 参数mSurface：之前保存的Android Surface对象（仍然有效）
        // 💡 使用时机：在更新EglCore引用后立即重建Surface
        createWindowSurface(mSurface);
    }
}
