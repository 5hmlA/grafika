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

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Layout that adjusts to maintain a specific aspect ratio.
 * 
 * 📐 宽高比布局：自动调整以保持指定的宽高比
 */
public class AspectFrameLayout extends FrameLayout {
    private static final String TAG = MainActivity.TAG + "-AFL";

    private double mTargetAspect = -1.0;        // 🎯 目标宽高比，-1表示使用默认窗口大小

    public AspectFrameLayout(Context context) {
        super(context);
    }

    public AspectFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Sets the desired aspect ratio.  The value is <code>width / height</code>.
     *
     * 🎯 设置期望的宽高比
     * @param aspectRatio 宽高比值（宽度/高度）
     */
    public void setAspectRatio(double aspectRatio) {
        // 🔍 参数验证：确保宽高比为非负值
        // 📌 作用：防止设置无效的负数宽高比
        // ⏰ 使用时机：方法入口，立即验证参数
        if (aspectRatio < 0) {
            // ❌ 抛出非法参数异常：宽高比不能为负数
            throw new IllegalArgumentException();
        }
        // 📝 输出调试日志：记录新的目标值和旧的目标值
        // 📌 作用：方便调试时追踪宽高比的变化历史
        // 💡 使用时机：每次调用此方法时都会输出
        Log.d(TAG, "Setting aspect ratio to " + aspectRatio + " (was " + mTargetAspect + ")");
        // 🔍 检查宽高比是否真的发生了变化
        // 📌 作用：避免重复设置相同比例时触发不必要的布局刷新
        // ⏰ 使用时机：在验证参数有效后，更新前进行判断
        if (mTargetAspect != aspectRatio) {
            // 🎯 更新目标宽高比为新值
            // 📌 作用：存储新的宽高比，供 onMeasure() 使用
            mTargetAspect = aspectRatio;
            // 🔄 请求重新布局：触发 onMeasure() 重新计算子视图尺寸
            // 📌 作用：通知系统需要根据新宽高比重新测量和布局
            // ⏰ 使用时机：宽高比变化后立即调用
            requestLayout();
        }
    }

    /**
     * 📐 测量子视图，根据目标宽高比调整尺寸
     * 💡 通过修改 MeasureSpec 来强制子视图按指定宽高比显示
     * @param widthMeasureSpec 宽度测量规格（包含尺寸和模式）
     * @param heightMeasureSpec 高度测量规格（包含尺寸和模式）
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // 📝 输出调试日志：目标宽高比和原始测量规格
        Log.d(TAG, "onMeasure target=" + mTargetAspect +
                " width=[" + MeasureSpec.toString(widthMeasureSpec) +
                "] height=[" + View.MeasureSpec.toString(heightMeasureSpec) + "]");

        // 🎯 仅当设置了目标宽高比时才进行调整
        if (mTargetAspect > 0) {
            // 📏 initialWidth - 初始宽度（像素）
            // 📌 作用：从 MeasureSpec 中提取父容器允许的最大宽度
            // ⏰ 使用时机：作为宽高比调整的基础尺寸
            int initialWidth = MeasureSpec.getSize(widthMeasureSpec);

            // 📏 initialHeight - 初始高度（像素）
            // 📌 作用：从 MeasureSpec 中提取父容器允许的最大高度
            // ⏰ 使用时机：作为宽高比调整的基础尺寸
            int initialHeight = MeasureSpec.getSize(heightMeasureSpec);

            // 🔲 horizPadding - 水平内边距总和（像素）
            // 📌 作用：计算左右 padding 占用的空间
            // 💡 需要从可用宽度中扣除，得到实际内容区域宽度
            int horizPadding = getPaddingLeft() + getPaddingRight();

            // 🔲 vertPadding - 垂直内边距总和（像素）
            // 📌 作用：计算上下 padding 占用的空间
            // 💡 需要从可用高度中扣除，得到实际内容区域高度
            int vertPadding = getPaddingTop() + getPaddingBottom();

            // 🔧 扣除 padding，得到实际可用的内容区域尺寸
            initialWidth -= horizPadding;
            initialHeight -= vertPadding;

            // 📐 viewAspectRatio - 当前视图的宽高比
            // 📌 作用：计算当前可用空间的实际宽高比
            // ⏰ 使用时机：与目标宽高比比较，决定如何调整
            double viewAspectRatio = (double) initialWidth / initialHeight;

            // 📊 aspectDiff - 宽高比差异（相对值）
            // 📌 作用：量化当前宽高比与目标宽高比的差距
            // 💡 计算方式：(目标比 / 当前比) - 1
            // ⏰ 使用时机：>0 表示太窄，<0 表示太宽
            double aspectDiff = mTargetAspect / viewAspectRatio - 1;

            // 🔍 判断宽高比是否已经足够接近（误差小于1%）
            if (Math.abs(aspectDiff) < 0.01) {
                // ✅ 已经很接近了，不需要调整
                Log.d(TAG, "aspect ratio is good (target=" + mTargetAspect +
                        ", view=" + initialWidth + "x" + initialHeight + ")");
            } else {
                // 🔄 需要调整尺寸以匹配目标宽高比

                if (aspectDiff > 0) {
                    // 📏 宽度限制：当前视图太窄，需要调整高度
                    // 💡 保持宽度不变，按目标比例缩小高度
                    // 🔧 计算方式：高度 = 宽度 / 目标宽高比
                    initialHeight = (int) (initialWidth / mTargetAspect);
                } else {
                    // 📏 高度限制：当前视图太矮，需要调整宽度
                    // 💡 保持高度不变，按目标比例缩小宽度
                    // 🔧 计算方式：宽度 = 高度 × 目标宽高比
                    initialWidth = (int) (initialHeight * mTargetAspect);
                }

                // 📝 输出调整后的尺寸和 padding 信息
                Log.d(TAG, "new size=" + initialWidth + "x" + initialHeight + " + padding " +
                        horizPadding + "x" + vertPadding);

                // 🔧 加回 padding，得到最终的测量尺寸
                initialWidth += horizPadding;
                initialHeight += vertPadding;

                // 📐 重新构建 MeasureSpec，使用 EXACTLY 模式
                // 📌 作用：强制子视图按计算出的精确尺寸显示
                // 💡 EXACTLY 模式：子视图必须使用指定的尺寸
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(initialWidth, MeasureSpec.EXACTLY);
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(initialHeight, MeasureSpec.EXACTLY);
            }
        }

        // 🚀 调用父类方法完成实际测量
        // 📌 作用：将处理后的 MeasureSpec 传递给子视图
        // ⏰ 使用时机：所有调整完成后，最后执行
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
