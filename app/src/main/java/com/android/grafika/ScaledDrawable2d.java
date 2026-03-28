/*
 * Copyright 2014 Google Inc. All rights reserved.
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

import android.util.Log;
import com.android.grafika.gles.Drawable2d;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Tweaked version of Drawable2d that rescales the texture coordinates to provide a
 * "zoom" effect.
 * 
 * 🔍 缩放版Drawable2d：重新缩放纹理坐标以实现"缩放"效果
 */
public class ScaledDrawable2d extends Drawable2d {
    private static final String TAG = MainActivity.TAG;
    private static final int SIZEOF_FLOAT = 4;

    private FloatBuffer mTweakedTexCoordArray;  // 🎨 调整后的纹理坐标数组
    private float mScale = 1.0f;                // 🔍 缩放因子（0.0-1.0）
    private boolean mRecalculate;                // 🔄 是否需要重新计算

    /** 构造函数 */
    public ScaledDrawable2d(Prefab shape) {
        super(shape);
        mRecalculate = true;
    }

    /**
     * Set the scale factor.
     *
     * 🔍 设置缩放因子
     * @param scale 缩放值（0.0-1.0）
     */
    public void setScale(float scale) {
        // 🔍 参数验证：确保缩放因子在有效范围内
        // 📌 作用：防止设置超出0.0-1.0范围的缩放值
        // ⏰ 使用时机：方法入口，立即验证参数
        if (scale < 0.0f || scale > 1.0f) {
            // ❌ 抛出运行时异常：缩放值必须在0.0-1.0之间
            // 💡 1.0表示原图，0.0表示完全缩放到中心点
            throw new RuntimeException("invalid scale " + scale);
        }
        // 🎯 更新缩放因子为新值
        // 📌 作用：存储缩放值，供 getTexCoordArray() 计算使用
        // ⏰ 使用时机：参数验证通过后立即设置
        mScale = scale;
        // 🔄 标记需要重新计算纹理坐标
        // 📌 作用：告诉 getTexCoordArray() 下次调用时需要重新计算
        // 💡 这是懒加载模式的标志位，避免每次都重新计算
        // ⏰ 使用时机：缩放因子变化后立即设置
        mRecalculate = true;
    }

    /**
     * Returns the array of texture coordinates with scaling applied.
     *
     * 📤 获取缩放后的纹理坐标数组
     */
    @Override
    public FloatBuffer getTexCoordArray() {
        // 🔍 检查是否需要重新计算纹理坐标
        // 📌 作用：懒加载优化，只在缩放因子变化时才重新计算
        // ⏰ 使用时机：每次获取纹理坐标前检查
        if (mRecalculate) {
            // 📥 从父类获取原始纹理坐标数组
            // 📌 作用：获取未缩放的原始纹理坐标作为计算基础
            // 💡 parentBuf 包含纹理坐标的 FloatBuffer
            FloatBuffer parentBuf = super.getTexCoordArray();

            // 📏 count - 纹理坐标元素总数
            // 📌 作用：获取父类缓冲区的容量，用于循环和内存分配
            // 💡 通常是顶点数量 × 坐标维度（如4个顶点 × 2维 = 8）
            // ⏰ 使用时机：创建新缓冲区和遍历坐标时使用
            int count = parentBuf.capacity();

            // 🔍 检查调整后的纹理坐标缓冲区是否已创建
            // 📌 作用：延迟初始化，只在第一次调用时分配内存
            // ⏰ 使用时机：首次获取缩放纹理坐标时
            if (mTweakedTexCoordArray == null) {
                // 📦 bb - 直接字节缓冲区
                // 📌 作用：在本地内存中分配空间，用于存储浮点纹理坐标
                // 💡 使用直接缓冲区是为了与 OpenGL ES 高效交互
                // ⏰ 使用时机：首次创建时分配，大小为 count × 4字节
                ByteBuffer bb = ByteBuffer.allocateDirect(count * SIZEOF_FLOAT);
                // 🔧 设置字节序为本地字节序
                // 📌 作用：确保与当前CPU架构的字节顺序一致
                // 💡 避免字节序不匹配导致的坐标数据错误
                bb.order(ByteOrder.nativeOrder());
                // 🔄 将字节缓冲区转换为浮点缓冲区
                // 📌 作用：创建用于存储纹理坐标的 FloatBuffer
                // 💡 一个float占4字节，所以能正确读写浮点数
                mTweakedTexCoordArray = bb.asFloatBuffer();
            }

            // 🎨 fb - 指向调整后的纹理坐标缓冲区
            // 📌 作用：局部引用，提高循环中的访问效率
            // ⏰ 使用时机：在循环中写入缩放后的坐标
            FloatBuffer fb = mTweakedTexCoordArray;

            // 🔍 scale - 缩放因子的局部副本
            // 📌 作用：避免在循环中重复访问成员变量，提高性能
            // 💡 缩放公式：(坐标 - 0.5) × 缩放 + 0.5
            // ⏰ 使用时机：在循环中应用于每个纹理坐标
            float scale = mScale;

            // 🔄 遍历所有纹理坐标并应用缩放
            // 📌 作用：以0.5为中心点进行缩放变换
            // ⏰ 使用时机：缩放因子变化后，首次获取纹理坐标时
            for (int i = 0; i < count; i++) {
                // 📥 fl - 从父类缓冲区获取原始纹理坐标值
                // 📌 作用：读取当前索引的原始纹理坐标（0.0-1.0范围）
                float fl = parentBuf.get(i);
                // 🔧 应用缩放变换公式：(fl - 0.5f) * scale + 0.5f
                // 📌 作用：以纹理中心(0.5)为基准进行缩放
                // 💡 scale=1.0时保持原样，scale=0.5时缩小到中心区域
                // ⏰ 使用时机：对每个纹理坐标进行变换
                fl = ((fl - 0.5f) * scale) + 0.5f;
                // 📤 将缩放后的坐标写入调整后的缓冲区
                // 📌 作用：存储变换后的纹理坐标
                // ⏰ 使用时机：计算完成后立即写入
                fb.put(i, fl);
            }

            // ✅ 标记计算完成，避免重复计算
            // 📌 作用：重置重新计算标志，下次调用时直接返回缓存结果
            // ⏰ 使用时机：所有纹理坐标计算完成后
            mRecalculate = false;
        }

        // 📤 返回缩放后的纹理坐标数组
        // 📌 作用：供渲染管线使用进行纹理映射
        // ⏰ 使用时机：OpenGL绑定纹理坐标时调用
        return mTweakedTexCoordArray;
    }
}
