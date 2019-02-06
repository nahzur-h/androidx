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

import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.MainThread;
import android.support.annotation.Nullable;
import android.support.annotation.RestrictTo;
import android.support.annotation.RestrictTo.Scope;
import android.support.annotation.UiThread;
import android.util.Log;
import android.util.Rational;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import androidx.camera.core.CameraX.LensFacing;
import androidx.camera.core.ImageOutputConfiguration.RotationValue;
import com.google.auto.value.AutoValue;
import java.util.Map;
import java.util.Objects;

/**
 * A use case that provides a camera preview stream for a view finder.
 *
 * <p>The preview stream is connected to an underlying {@link SurfaceTexture}. The caller is still
 * responsible for deciding how this texture is shown.
 */
public class ViewFinderUseCase extends BaseUseCase {
  private static final String TAG = "ViewFinderUseCase";

  /**
   * Provides a static configuration with implementation-agnostic options.
   *
   * @hide
   */
  @RestrictTo(Scope.LIBRARY_GROUP)
  public static final Defaults DEFAULT_CONFIG = new Defaults();

  /**
   * Provides a base static default configuration for the ViewFinderUseCase
   *
   * <p>These values may be overridden by the implementation. They only provide a minimum set of
   * defaults that are implementation independent.
   *
   * @hide
   */
  @RestrictTo(Scope.LIBRARY_GROUP)
  public static final class Defaults
      implements ConfigurationProvider<ViewFinderUseCaseConfiguration> {
    private static final Handler DEFAULT_HANDLER = new Handler(Looper.getMainLooper());
    private static final Rational DEFAULT_ASPECT_RATIO = new Rational(16, 9);
    private static final Size DEFAULT_MAX_RESOLUTION = CameraX.getSurfaceManager().getPreviewSize();
    private static final int DEFAULT_SURFACE_OCCUPANCY_PRIORITY = 2;

    private static final ViewFinderUseCaseConfiguration DEFAULT_CONFIG;

    static {
      ViewFinderUseCaseConfiguration.Builder builder =
          new ViewFinderUseCaseConfiguration.Builder()
              .setCallbackHandler(DEFAULT_HANDLER)
              .setTargetAspectRatio(DEFAULT_ASPECT_RATIO)
              .setMaxResolution(DEFAULT_MAX_RESOLUTION)
              .setSurfaceOccupancyPriority(DEFAULT_SURFACE_OCCUPANCY_PRIORITY);
      DEFAULT_CONFIG = builder.build();
    }

    @Override
    public ViewFinderUseCaseConfiguration getConfiguration() {
      return DEFAULT_CONFIG;
    }
  }

  /** Describes the error that occurred during viewfinder operation. */
  public enum UseCaseError {
    /** Unknown error occurred. See message or log for more details. */
    UNKNOWN_ERROR
  }

  /** A listener of {@link ViewFinderOutput}. */
  public interface OnViewFinderOutputUpdateListener {
    /** Callback when ViewFinderOutput has been updated. */
    void onUpdated(ViewFinderOutput output);
  }

  /**
   * Sets a listener to get the {@link ViewFinderOutput} updates.
   *
   * <p>Setting this listener will signal to the camera that the use case is ready to receive data.
   * Setting the listener to {@code null} will signal to the camera that the camera should no longer
   * stream data to the last {@link ViewFinderOutput}.
   *
   * <p>Once {@link OnViewFinderOutputUpdateListener#onUpdated(ViewFinderOutput)} is called,
   * ownership of the {@link ViewFinderOutput} and its contents is transferred to the user. It is
   * the user's responsibility to release the last {@link SurfaceTexture} returned by {@link
   * ViewFinderOutput#getSurfaceTexture()} when a new SurfaceTexture is provided via an update or
   * when the user is finished with the use case.
   *
   * @param listener The listener which will receive {@link ViewFinderOutput} updates.
   */
  @UiThread
  public void setOnViewFinderOutputUpdateListener(
      @Nullable OnViewFinderOutputUpdateListener newListener) {
    OnViewFinderOutputUpdateListener oldListener = subscribedViewFinderOutputListener;
    subscribedViewFinderOutputListener = newListener;
    if (oldListener == null && newListener != null) {
      notifyActive();
      if (latestViewFinderOutput != null) {
        surfaceDispatched = true;
        newListener.onUpdated(latestViewFinderOutput);
      }
    } else if (oldListener != null && newListener == null) {
      notifyInactive();
    } else if (oldListener != null && oldListener != newListener) {
      if (latestViewFinderOutput != null) {
        checkedSurfaceTexture.resetSurfaceTexture();
      }
    }
  }

  /**
   * Removes previously ViewFinderOutput listener.
   *
   * <p>This is equivalent to calling {@code setOnViewFinderOutputUpdateListener(null)}.
   */
  @UiThread
  public void removeViewFinderOutputListener() {
    setOnViewFinderOutputUpdateListener(null);
  }

  /**
   * Gets {@link OnViewFinderOutputUpdateListener}
   *
   * @return the last set listener or {@code null} if no listener is set
   */
  @UiThread
  @Nullable
  public OnViewFinderOutputUpdateListener getOnViewFinderOutputUpdateListener() {
    return subscribedViewFinderOutputListener;
  }

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  @Nullable private OnViewFinderOutputUpdateListener subscribedViewFinderOutputListener;
  @Nullable private ViewFinderOutput latestViewFinderOutput;
  private boolean surfaceDispatched = false;
  private final CheckedSurfaceTexture.OnTextureChangedListener surfaceTextureListener =
      (newSurfaceTexture, newResolution) ->
          ViewFinderUseCase.this.updateOutput(newSurfaceTexture, newResolution);

  private final CheckedSurfaceTexture checkedSurfaceTexture =
      new CheckedSurfaceTexture(surfaceTextureListener, mainHandler);
  private final ViewFinderUseCaseConfiguration.Builder useCaseConfigBuilder;

  // TODO: Timeout may be exposed as a ViewFinderUseCaseConfiguration(moved to CameraControl)

  /** A bundle containing a {@link SurfaceTexture} and properties needed to display a ViewFinder. */
  @AutoValue
  public abstract static class ViewFinderOutput {

    static ViewFinderOutput create(
        SurfaceTexture surfaceTexture, Size textureSize, int rotationDegrees) {
      return new AutoValue_ViewFinderUseCase_ViewFinderOutput(
          surfaceTexture, textureSize, rotationDegrees);
    }

    /** Returns the ViewFinderOutput that receives image data. */
    public abstract SurfaceTexture getSurfaceTexture();

    /** Returns the dimensions of the ViewFinderOutput. */
    public abstract Size getTextureSize();

    /**
     * Returns the rotation required, in degrees, to transform the ViewFinderOutput to match the
     * orientation given by ImageOutputConfiguration#getTargetRotation(int).
     *
     * <p>This number is independent of any rotation value that can be derived from the
     * ViewFinderOutput's {@link SurfaceTexture#getTransformMatrix(float[])}.
     */
    public abstract int getRotationDegrees();

    ViewFinderOutput() {}
  }

  /**
   * Creates a new view finder use case from the given configuration.
   *
   * @param configuration for this use case instance
   */
  @MainThread
  public ViewFinderUseCase(ViewFinderUseCaseConfiguration configuration) {
    super(configuration);
    useCaseConfigBuilder = ViewFinderUseCaseConfiguration.Builder.fromConfig(configuration);
  }

  private CameraControl getCurrentCameraControl() {
    ViewFinderUseCaseConfiguration configuration =
        (ViewFinderUseCaseConfiguration) getUseCaseConfiguration();
    String cameraId = getCameraIdUnchecked(configuration.getLensFacing());
    return getCameraControl(cameraId);
  }

  /**
   * Adjusts the view finder according to the properties in some local regions.
   *
   * <p>The auto-focus (AF) and auto-exposure (AE) properties will be recalculated from the local
   * regions.
   *
   * @param focus rectangle with dimensions in sensor coordinate frame for focus
   * @param metering rectangle with dimensions in sensor coordinate frame for metering
   */
  public void focus(Rect focus, Rect metering) {
    focus(focus, metering, null);
  }

  /**
   * Adjusts the view finder according to the properties in some local regions with a callback
   * called once focus scan has completed.
   *
   * <p>The auto-focus (AF) and auto-exposure (AE) properties will be recalculated from the local
   * regions.
   *
   * @param focus rectangle with dimensions in sensor coordinate frame for focus
   * @param metering rectangle with dimensions in sensor coordinate frame for metering
   * @param listener listener for when focus has completed
   */
  public void focus(Rect focus, Rect metering, @Nullable OnFocusCompletedListener listener) {
    getCurrentCameraControl().focus(focus, metering, listener, mainHandler);
  }

  /**
   * Adjusts the view finder to zoom to a local region.
   *
   * @param crop rectangle with dimensions in sensor coordinate frame for zooming
   */
  public void zoom(Rect crop) {
    getCurrentCameraControl().setCropRegion(crop);
  }

  /**
   * Sets torch on/off.
   *
   * @param torch True if turn on torch, otherwise false
   */
  public void enableTorch(boolean torch) {
    getCurrentCameraControl().enableTorch(torch);
  }

  /** True if the torch is on */
  public boolean isTorchOn() {
    return getCurrentCameraControl().isTorchOn();
  }

  /**
   * Sets the rotation of the surface texture consumer.
   *
   * <p>In most cases this should be set to the current rotation returned by {@link
   * Display#getRotation()}. This will update the rotation value in {@link ViewFinderOutput} to
   * reflect the angle the ViewFinderOutput should be rotated to match the supplied rotation.
   *
   * @param rotation Rotation of the surface texture consumer.
   */
  public void setTargetRotation(@RotationValue int rotation) {
    ImageOutputConfiguration oldconfig = (ImageOutputConfiguration) getUseCaseConfiguration();
    int oldRotation = oldconfig.getTargetRotation(ImageOutputConfiguration.INVALID_ROTATION);
    if (oldRotation == ImageOutputConfiguration.INVALID_ROTATION || oldRotation != rotation) {
      useCaseConfigBuilder.setTargetRotation(rotation);
      updateUseCaseConfiguration(useCaseConfigBuilder.build());

      // TODO(b/122846516): Update session configuration and possibly reconfigure session.
      // For now we'll just attempt to update the rotation metadata.
      invalidateMetadata();
    }
  }

  @Override
  public String toString() {
    return TAG + ":" + getName();
  }

  /**
   * {@inheritDoc}
   *
   * @hide
   */
  @Override
  @Nullable
  protected UseCaseConfiguration.Builder<?, ?, ?> getDefaultBuilder() {
    ViewFinderUseCaseConfiguration defaults =
        CameraX.getDefaultUseCaseConfiguration(ViewFinderUseCaseConfiguration.class);
    if (defaults != null) {
      return ViewFinderUseCaseConfiguration.Builder.fromConfig(defaults);
    }

    return null;
  }

  private static SessionConfiguration.Builder createFrom(
      ViewFinderUseCaseConfiguration configuration, DeferrableSurface surface) {
    SessionConfiguration.Builder sessionConfigBuilder =
        SessionConfiguration.Builder.createFrom(configuration);
    sessionConfigBuilder.addSurface(surface);
    return sessionConfigBuilder;
  }

  /**
   * {@inheritDoc}
   *
   * @hide
   */
  @RestrictTo(Scope.LIBRARY_GROUP)
  @Override
  public void clear() {
    checkedSurfaceTexture.release();
    removeViewFinderOutputListener();
    notifyInactive();

    SurfaceTexture oldTexture =
        (latestViewFinderOutput == null) ? null : latestViewFinderOutput.getSurfaceTexture();
    if (oldTexture != null && !surfaceDispatched) {
      oldTexture.release();
    }

    super.clear();
  }

  /**
   * {@inheritDoc}
   *
   * @hide
   */
  @Override
  protected Map<String, Size> onSuggestedResolutionUpdated(
      Map<String, Size> suggestedResolutionMap) {
    ViewFinderUseCaseConfiguration configuration =
        (ViewFinderUseCaseConfiguration) getUseCaseConfiguration();
    String cameraId = getCameraIdUnchecked(configuration.getLensFacing());
    Size resolution = suggestedResolutionMap.get(cameraId);
    if (resolution == null) {
      throw new IllegalArgumentException(
          "Suggested resolution map missing resolution for camera " + cameraId);
    }

    checkedSurfaceTexture.setResolution(resolution);
    checkedSurfaceTexture.resetSurfaceTexture();

    SessionConfiguration.Builder sessionConfigBuilder =
        createFrom(configuration, checkedSurfaceTexture);
    attachToCamera(cameraId, sessionConfigBuilder.build());

    return suggestedResolutionMap;
  }

  private static final String getCameraIdUnchecked(LensFacing lensFacing) {
    try {
      return CameraX.getCameraWithLensFacing(lensFacing);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Unable to get camera id for camera lens facing " + lensFacing, e);
    }
  }

  @UiThread
  private void invalidateMetadata() {
    if (latestViewFinderOutput != null) {
      // Only update the output if we have a SurfaceTexture. Otherwise we'll wait until a
      // SurfaceTexture is ready.
      updateOutput(
          latestViewFinderOutput.getSurfaceTexture(), latestViewFinderOutput.getTextureSize());
    }
  }

  @UiThread
  private void updateOutput(SurfaceTexture surfaceTexture, Size resolution) {
    ViewFinderUseCaseConfiguration useCaseConfig =
        (ViewFinderUseCaseConfiguration) getUseCaseConfiguration();

    int relativeRotation =
        (latestViewFinderOutput == null) ? 0 : latestViewFinderOutput.getRotationDegrees();
    try {
      // Attempt to get the camera ID. If this fails, we probably don't have permission, so we
      // will rely on the updated UseCaseConfiguration to set the correct rotation in
      // onSuggestedResolutionUpdated()
      String cameraId = CameraX.getCameraWithLensFacing(useCaseConfig.getLensFacing());
      CameraInfo cameraInfo = CameraX.getCameraInfo(cameraId);
      relativeRotation =
          cameraInfo.getSensorRotationDegrees(useCaseConfig.getTargetRotation(Surface.ROTATION_0));
    } catch (CameraInfoUnavailableException e) {
      Log.e(TAG, "Unable to update output metadata: " + e);
    }

    ViewFinderOutput newOutput =
        ViewFinderOutput.create(surfaceTexture, resolution, relativeRotation);

    // Only update the output if something has changed
    if (!Objects.equals(latestViewFinderOutput, newOutput)) {
      SurfaceTexture oldTexture =
          (latestViewFinderOutput == null) ? null : latestViewFinderOutput.getSurfaceTexture();
      OnViewFinderOutputUpdateListener outputListener = getOnViewFinderOutputUpdateListener();

      latestViewFinderOutput = newOutput;

      boolean textureChanged = oldTexture != surfaceTexture;
      if (textureChanged) {
        // If the old surface was never dispatched, we can safely release the old SurfaceTexture.
        if (oldTexture != null && !surfaceDispatched) {
          oldTexture.release();
        }

        // Keep track of whether this SurfaceTexture is dispatched
        surfaceDispatched = false;
      }

      if (outputListener != null) {
        // If we have a listener, then we should be active and we require a reset if the
        // SurfaceTexture changed.
        if (textureChanged) {
          notifyReset();
        }

        surfaceDispatched = true;
        outputListener.onUpdated(newOutput);
      }
    }
  }
}
