/*
 * Copyright 2019 The Android Open Source Project
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

package androidx.camera.core;

import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;

/**
 * A {@link CaptureRequestConfig} with an identifier.
 *
 * @hide
 */
@RestrictTo(Scope.LIBRARY_GROUP)
public interface CaptureStage {

    /** Returns the identifier for the capture. */
    int getId();

    /**
     * Returns the configuration for the capture.
     */
    CaptureRequestConfig getCaptureRequestConfig();

    /**
     * A capture stage which contains no additional implementation options
     */
    final class DefaultCaptureStage implements CaptureStage {
        private final CaptureRequestConfig mCaptureRequestConfig;

        DefaultCaptureStage() {
            CaptureRequestConfig.Builder builder = new CaptureRequestConfig.Builder();
            mCaptureRequestConfig = builder.build();
        }

        @Override
        public int getId() {
            return 0;
        }

        @Override
        public CaptureRequestConfig getCaptureRequestConfig() {
            return mCaptureRequestConfig;
        }
    }
}
