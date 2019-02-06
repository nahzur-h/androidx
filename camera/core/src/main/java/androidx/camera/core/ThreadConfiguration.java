/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.support.annotation.RestrictTo.Scope;

/** Configuration containing options pertaining to threads used by the configured object. */
public interface ThreadConfiguration extends Configuration.Reader {

  /**
   * Returns the default handler that will be used for callbacks.
   *
   * @param valueIfMissing The value to return if this configuration option has not been set.
   * @return The stored value or <code>valueIfMissing</code> if the value does not exist in this
   *     configuration.
   */
  @Nullable
  default Handler getCallbackHandler(@Nullable Handler valueIfMissing) {
    return retrieveOption(OPTION_CALLBACK_HANDLER, valueIfMissing);
  }

  /**
   * Returns the default handler that will be used for callbacks.
   *
   * @return The stored value, if it exists in this configuration.
   * @throws IllegalArgumentException if the option does not exist in this configuration.
   */
  default Handler getCallbackHandler() {
    return retrieveOption(OPTION_CALLBACK_HANDLER);
  }

  /**
   * Builder for a {@link ThreadConfiguration}.
   *
   * @param <C> The top level configuration which will be generated by {@link #build()}.
   * @param <B> The top level builder type for which this builder is composed with.
   */
  interface Builder<C extends Configuration, B extends Builder<C, B>>
      extends Configuration.Builder<C, B> {

    /**
     * {@inheritDoc}
     *
     * @hide
     */
    @RestrictTo(Scope.LIBRARY_GROUP)
    @Override
    MutableConfiguration getMutableConfiguration();

    /**
     * Sets the default handler that will be used for callbacks.
     *
     * @param handler The handler which will be used to post callbacks.
     * @return the current Builder.
     */
    default B setCallbackHandler(Handler handler) {
      getMutableConfiguration().insertOption(OPTION_CALLBACK_HANDLER, handler);
      return builder();
    }
  }

  // Option Declarations:
  // ***********************************************************************************************

  /**
   * Option: camerax.core.thread.callbackHandler
   *
   * @hide
   */
  @RestrictTo(Scope.LIBRARY_GROUP)
  Option<Handler> OPTION_CALLBACK_HANDLER =
      Option.create("camerax.core.thread.callbackHandler", Handler.class);
}
