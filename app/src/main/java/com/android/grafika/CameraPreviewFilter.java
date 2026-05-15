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

import android.util.Log;

import com.android.grafika.gles.FullFrameRect;
import com.android.grafika.gles.Texture2dProgram;

/**
 * 🎛️ 相机预览着色器滤镜（与 {@link CameraCaptureActivity} 下拉框索引一致）。
 * <p>
 * 抽成独立工具类，让 {@link CameraCaptureActivity.CameraSurfaceRenderer} 与
 * {@link TextureMovieEncoder} 共用同一套配置，避免预览与录制效果不一致。
 */
final class CameraPreviewFilter {
    private static final String TAG = MainActivity.TAG;

    private CameraPreviewFilter() {
    }

    /**
     * 根据滤镜枚举切换 {@link Texture2dProgram}，并在已知纹理尺寸时写入 {@code setTexSize}。
     *
     * @param filterMode    {@link CameraCaptureActivity#FILTER_NONE} 等常量
     * @param fullScreen    全屏绘制封装（外部 OES 纹理）
     * @param texWidth      相机预览纹理宽；未知时可传 ≤0（跳过 setTexSize）
     * @param texHeight     相机预览纹理高
     */
    static void apply(int filterMode, FullFrameRect fullScreen, int texWidth, int texHeight) {
        Texture2dProgram.ProgramType programType;
        float[] kernel = null;
        float colorAdj = 0.0f;

        switch (filterMode) {
            case CameraCaptureActivity.FILTER_NONE:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT;
                break;
            case CameraCaptureActivity.FILTER_BLACK_WHITE:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_BW;
                break;
            case CameraCaptureActivity.FILTER_BLUR:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                kernel = new float[] {
                        1f / 16f, 2f / 16f, 1f / 16f,
                        2f / 16f, 4f / 16f, 2f / 16f,
                        1f / 16f, 2f / 16f, 1f / 16f };
                break;
            case CameraCaptureActivity.FILTER_SHARPEN:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                kernel = new float[] {
                        0f, -1f, 0f,
                        -1f, 5f, -1f,
                        0f, -1f, 0f };
                break;
            case CameraCaptureActivity.FILTER_EDGE_DETECT:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                kernel = new float[] {
                        -1f, -1f, -1f,
                        -1f, 8f, -1f,
                        -1f, -1f, -1f };
                break;
            case CameraCaptureActivity.FILTER_EMBOSS:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                kernel = new float[] {
                        2f, 0f, 0f,
                        0f, -1f, 0f,
                        0f, 0f, -1f };
                colorAdj = 0.5f;
                break;
            default:
                throw new RuntimeException("Unknown filter mode " + filterMode);
        }

        if (programType != fullScreen.getProgram().getProgramType()) {
            Log.d(TAG, "CameraPreviewFilter: switching program to " + programType);
            fullScreen.changeProgram(new Texture2dProgram(programType));
        }

        if (kernel != null) {
            fullScreen.getProgram().setKernel(kernel, colorAdj);
        }

        if (texWidth > 0 && texHeight > 0) {
            fullScreen.getProgram().setTexSize(texWidth, texHeight);
        }
    }
}
