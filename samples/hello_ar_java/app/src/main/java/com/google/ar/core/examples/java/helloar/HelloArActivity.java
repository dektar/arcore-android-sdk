/*
 * Copyright 2017 Google LLC
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

package com.google.ar.core.examples.java.helloar;

import android.content.DialogInterface;
import android.content.res.Resources;
import android.media.Image;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.ArCoreApk.Availability;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.LightEstimate;
import com.google.ar.core.Plane;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingFailureReason;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DepthSettings;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SineEnvelope;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TapHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.samplerender.Framebuffer;
import com.google.ar.core.examples.java.common.samplerender.GLError;
import com.google.ar.core.examples.java.common.samplerender.Mesh;
import com.google.ar.core.examples.java.common.samplerender.SampleRender;
import com.google.ar.core.examples.java.common.samplerender.Shader;
import com.google.ar.core.examples.java.common.samplerender.Texture;
import com.google.ar.core.examples.java.common.samplerender.VertexBuffer;
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer;
import com.google.ar.core.examples.java.common.samplerender.arcore.SpecularCubemapFilter;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.jsyn.JSyn;
import com.jsyn.Synthesizer;
import com.jsyn.devices.android.AndroidAudioForJSyn;
import com.jsyn.unitgen.EnvelopeDAHDSR;
import com.jsyn.unitgen.LineOut;
import com.softsynth.math.AudioMath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3D model.
 */
public class HelloArActivity extends AppCompatActivity implements SampleRender.Renderer {

  private static final String TAG = HelloArActivity.class.getSimpleName();

  private static final String SEARCHING_PLANE_MESSAGE = "Searching for surfaces...";
  private static final String WAITING_FOR_TAP_MESSAGE = "Tap on the screen to place a target.";

  // See the definition of updateSphericalHarmonicsCoefficients for an explanation of these
  // constants.
  private static final float[] sphericalHarmonicFactors = {
    0.282095f,
    -0.325735f,
    0.325735f,
    -0.325735f,
    0.273137f,
    -0.273137f,
    0.078848f,
    -0.273137f,
    0.136569f,
  };

  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 100f;

  private static final int CUBEMAP_RESOLUTION = 16;
  private static final int CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32;

  private static final float TARGET_DISTANCE_ALONG_RAY = 2.0f;

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;

  private boolean installRequested;

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
  private TapHelper tapHelper;
  private SampleRender render;

  private PlaneRenderer planeRenderer;
  private BackgroundRenderer backgroundRenderer;
  private Framebuffer virtualSceneFramebuffer;
  private boolean hasSetTextureNames = false;

  private final DepthSettings depthSettings = new DepthSettings();
  private boolean[] depthSettingsMenuDialogCheckboxes = new boolean[2];

  // Point Cloud
  private VertexBuffer pointCloudVertexBuffer;
  private Mesh pointCloudMesh;
  private Shader pointCloudShader;
  // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
  // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
  private long lastPointCloudTimestamp = 0;

  // Virtual object (ARCore pawn)
  private Mesh virtualObjectMesh;
  private Shader virtualObjectShader;
  private Texture virtualObjectAlbedoTexture;

  private final List<WrappedAnchor> wrappedAnchors = new ArrayList<>();

  // Environmental HDR
  private Texture dfgTexture;
  private SpecularCubemapFilter cubemapFilter;

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] modelMatrix = new float[16];
  private final float[] viewMatrix = new float[16];
  private final float[] projectionMatrix = new float[16];
  private final float[] modelViewMatrix = new float[16]; // view x model
  private final float[] modelViewProjectionMatrix = new float[16]; // projection x view x model
  private final float[] sphericalHarmonicsCoefficients = new float[9 * 3];
  private final float[] viewInverseMatrix = new float[16];
  private final float[] worldLightDirection = {0.0f, 0.0f, 0.0f, 0.0f};
  private final float[] viewLightDirection = new float[4]; // view x world light direction

  private long lastAnchorUpdateTimestamp = 0;

  private boolean mHasBeenClicked = false;
  private View.OnClickListener mSurfaceClickListener = null;

  private float STRAIGHT_ENOUGH_THETA = 1.5f;

  // TODO: Put audio in its own class.
  private Synthesizer synth;
  private AndroidAudioForJSyn audioManager;
  private LineOut lineOut;
  private SineEnvelope voiceL;
  private SineEnvelope voiceR;
  private boolean mIsSonifying = false;
  private static final int SAMPLE_RATE = 44100;
  protected static final double FREQ_MIN = 220.;
  protected static final double FREQ_MAX = 783.991;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(/* context= */ this);

    // Set up touch listener.
//    tapHelper = new TapHelper(/* context= */ this);
//    surfaceView.setOnTouchListener(tapHelper);
    surfaceView.setOnClickListener(mSurfaceClickListener = new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        mHasBeenClicked = true;
      }
    });

    // Set up renderer.
    render = new SampleRender(surfaceView, this, getAssets());

    installRequested = false;

    depthSettings.onCreate(this);
    ImageButton settingsButton = findViewById(R.id.settings_button);
    settingsButton.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View v) {
            PopupMenu popup = new PopupMenu(HelloArActivity.this, v);
            popup.setOnMenuItemClickListener(HelloArActivity.this::settingsMenuClick);
            popup.inflate(R.menu.settings_menu);
            popup.show();
          }
        });
  }

  /** Menu button to launch feature specific settings. */
  protected boolean settingsMenuClick(MenuItem item) {
    if (item.getItemId() == R.id.depth_settings) {
      launchDepthSettingsMenuDialog();
      return true;
    }
    return false;
  }

  @Override
  protected void onDestroy() {
    if (session != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      session.close();
      session = null;
    }

    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        // Always check the latest availability.
        Availability availability = ArCoreApk.getInstance().checkAvailability(this);

        // In all other cases, try to install ARCore and handle installation failures.
        if (availability != Availability.SUPPORTED_INSTALLED) {
          switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
            case INSTALL_REQUESTED:
              installRequested = true;
              return;
            case INSTALLED:
              break;
          }
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // Create the session.
        session = new Session(/* context= */ this);
      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      configureSession();
      // To record a live camera session for later playback, call
      // `session.startRecording(recordingConfig)` at anytime. To playback a previously recorded AR
      // session instead of using the live camera feed, call
      // `session.setPlaybackDatasetUri(Uri)` before calling `session.resume()`. To
      // learn more about recording and playback, see:
      // https://developers.google.com/ar/develop/java/recording-and-playback
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
    if (synth != null) {
      synth.stop();
      synth = null;
    }
    if (lineOut != null) {
      lineOut.stop();
      lineOut = null;
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(SampleRender render) {
    // Prepare the rendering objects. This involves reading shaders and 3D model files, so may throw
    // an IOException.
    try {
      planeRenderer = new PlaneRenderer(render);
      backgroundRenderer = new BackgroundRenderer(render);
      virtualSceneFramebuffer = new Framebuffer(render, /* width= */ 1, /* height= */ 1);

      cubemapFilter =
          new SpecularCubemapFilter(
              render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES);
      // Load DFG lookup table for environmental lighting
      dfgTexture =
          new Texture(
              render,
              Texture.Target.TEXTURE_2D,
              Texture.WrapMode.CLAMP_TO_EDGE,
              /* useMipmaps= */ false);
      // The dfg.raw file is a raw half-float texture with two channels.
      final int dfgResolution = 64;
      final int dfgChannels = 2;
      final int halfFloatSize = 2;

      ByteBuffer buffer =
          ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize);
      try (InputStream is = getAssets().open("models/dfg.raw")) {
        is.read(buffer.array());
      }
      // SampleRender abstraction leaks here.
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture.getTextureId());
      GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture");
      GLES30.glTexImage2D(
          GLES30.GL_TEXTURE_2D,
          /* level= */ 0,
          GLES30.GL_RG16F,
          /* width= */ dfgResolution,
          /* height= */ dfgResolution,
          /* border= */ 0,
          GLES30.GL_RG,
          GLES30.GL_HALF_FLOAT,
          buffer);
      GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D");

      // Point cloud
      pointCloudShader =
          Shader.createFromAssets(
                  render,
                  "shaders/point_cloud.vert",
                  "shaders/point_cloud.frag",
                  /* defines= */ null)
              .setVec4(
                  "u_Color", new float[] {31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f})
              .setFloat("u_PointSize", 5.0f);
      // four entries per vertex: X, Y, Z, confidence
      pointCloudVertexBuffer =
          new VertexBuffer(render, /* numberOfEntriesPerVertex= */ 4, /* entries= */ null);
      final VertexBuffer[] pointCloudVertexBuffers = {pointCloudVertexBuffer};
      pointCloudMesh =
          new Mesh(
              render, Mesh.PrimitiveMode.POINTS, /* indexBuffer= */ null, pointCloudVertexBuffers);

      // Virtual object to render (ARCore pawn)
      virtualObjectAlbedoTexture =
          Texture.createFromAsset(
              render,
              "models/pawn_albedo.png",
              Texture.WrapMode.CLAMP_TO_EDGE,
              Texture.ColorFormat.SRGB);
      Texture virtualObjectPbrTexture =
          Texture.createFromAsset(
              render,
              "models/pawn_roughness_metallic_ao.png",
              Texture.WrapMode.CLAMP_TO_EDGE,
              Texture.ColorFormat.LINEAR);

      virtualObjectMesh = Mesh.createFromAsset(render, "models/pawn.obj");
      virtualObjectShader =
          Shader.createFromAssets(
                  render,
                  "shaders/environmental_hdr.vert",
                  "shaders/environmental_hdr.frag",
                  /* defines= */ new HashMap<String, String>() {
                    {
                      put(
                          "NUMBER_OF_MIPMAP_LEVELS",
                          Integer.toString(cubemapFilter.getNumberOfMipmapLevels()));
                    }
                  })
              .setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture)
              .setTexture("u_RoughnessMetallicAmbientOcclusionTexture", virtualObjectPbrTexture)
              .setTexture("u_Cubemap", cubemapFilter.getFilteredCubemapTexture())
              .setTexture("u_DfgTexture", dfgTexture);
    } catch (IOException e) {
      Log.e(TAG, "Failed to read a required asset file", e);
      messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
    }
  }

  @Override
  public void onSurfaceChanged(SampleRender render, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    virtualSceneFramebuffer.resize(width, height);
  }

  @Override
  public void onDrawFrame(SampleRender render) {
    if (session == null) {
      return;
    }

    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(
          new int[] {backgroundRenderer.getCameraColorTexture().getTextureId()});
      hasSetTextureNames = true;
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    // Obtain the current frame from the AR Session. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    Frame frame;
    try {
      frame = session.update();
    } catch (CameraNotAvailableException e) {
      Log.e(TAG, "Camera not available during onDrawFrame", e);
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      return;
    }
    Camera camera = frame.getCamera();

    // Update BackgroundRenderer state to match the depth settings.
    try {
      backgroundRenderer.setUseDepthVisualization(
          render, depthSettings.depthColorVisualizationEnabled());
      backgroundRenderer.setUseOcclusion(render, depthSettings.useDepthForOcclusion());
    } catch (IOException e) {
      Log.e(TAG, "Failed to read a required asset file", e);
      messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
      return;
    }
    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame);

    if (camera.getTrackingState() == TrackingState.TRACKING
        && (depthSettings.useDepthForOcclusion()
            || depthSettings.depthColorVisualizationEnabled())) {
      try (Image depthImage = frame.acquireDepthImage16Bits()) {
        backgroundRenderer.updateCameraDepthTexture(depthImage);
      } catch (NotYetAvailableException e) {
        // This normally means that depth data is not available yet. This is normal so we will not
        // spam the logcat with this.
      }
    }

    // Handle one tap per frame.
    handleTap(frame, camera);

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

    // Show a message based on whether tracking has failed, if planes are detected, and if the user
    // has placed any objects.
    String message = null;
    float[] offsets = {0, 0, 0};
    if (wrappedAnchors.size() == 2 && camera.getTrackingState() == TrackingState.TRACKING) {
      offsets = getOffsetsForCurrentCameraPose(camera);
      message = getMessageForCurrentPoseOffsets(offsets[0], offsets[1], offsets[2]);
      // TODO: Could we use the depth image to set the target point as far away as possible?
    }
    if (camera.getTrackingState() == TrackingState.PAUSED) {
      if (camera.getTrackingFailureReason() == TrackingFailureReason.NONE) {
        message = SEARCHING_PLANE_MESSAGE;
      } else {
        message = TrackingStateHelper.getTrackingFailureReasonString(camera);
      }
      pauseSonification();
    } else if (hasTrackingPlane()) {
      if (wrappedAnchors.isEmpty()) {
        message = WAITING_FOR_TAP_MESSAGE;
        surfaceView.setContentDescription(WAITING_FOR_TAP_MESSAGE);
      }
      sonifyCurrentOffsets(offsets[0], offsets[2]);
    } else {
      message = SEARCHING_PLANE_MESSAGE;
      surfaceView.setContentDescription(SEARCHING_PLANE_MESSAGE);
    }
    if (message == null) {
      messageSnackbarHelper.hide(this);
    } else {
      messageSnackbarHelper.showMessage(this, message);
    }

    // -- Draw background

    if (frame.getTimestamp() != 0) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render);
    }

    // If not tracking, don't draw 3D objects.
    if (camera.getTrackingState() == TrackingState.PAUSED) {
      return;
    }

    // -- Draw non-occluded virtual objects (planes, point cloud)

    // Get projection matrix.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR);

    // Get camera matrix and draw.
    camera.getViewMatrix(viewMatrix, 0);

    // Visualize tracked points.
    // Use try-with-resources to automatically release the point cloud.
    try (PointCloud pointCloud = frame.acquirePointCloud()) {
      if (pointCloud.getTimestamp() > lastPointCloudTimestamp) {
        pointCloudVertexBuffer.set(pointCloud.getPoints());
        lastPointCloudTimestamp = pointCloud.getTimestamp();
      }
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0);
      pointCloudShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix);
      render.draw(pointCloudMesh, pointCloudShader);
    }

    // Visualize planes.
    planeRenderer.drawPlanes(
        render,
        session.getAllTrackables(Plane.class),
        camera.getDisplayOrientedPose(),
        projectionMatrix);

    // -- Draw occluded virtual objects

    // Update lighting parameters in the shader
    updateLightEstimation(frame.getLightEstimate(), viewMatrix);

    // Visualize anchors created by touch.
    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f);
    for (WrappedAnchor wrappedAnchor : wrappedAnchors) {
      Anchor anchor = wrappedAnchor.getAnchor();
      Trackable trackable = wrappedAnchor.getTrackable();
      if (anchor.getTrackingState() != TrackingState.TRACKING) {
        continue;
      }

      // Get the current pose of an Anchor in world space. The Anchor pose is updated
      // during calls to session.update() as ARCore refines its estimate of the world.
      anchor.getPose().toMatrix(modelMatrix, 0);

      // Calculate model/view/projection matrices
      Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0);
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0);

      // Update shader properties and draw
      virtualObjectShader.setMat4("u_ModelView", modelViewMatrix);
      virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix);
      virtualObjectShader.setTexture("u_AlbedoTexture", virtualObjectAlbedoTexture);

      render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer);
    }

    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR);
  }

  private float[] getOffsetsForCurrentCameraPose(Camera camera) {
    // Get diff between current camera pose and target ray / point, and instruct the user
    // how to go in the right direction in world coordinates.
    // First, get the current position, including camera rotation (we need to know if you are
    // facing twds or away)
    Pose cameraPose = camera.getDisplayOrientedPose();

    // Undo any z-axis rotation. We still have y rotation (left/right) and x rotation (up/down).
    cameraPose = makePortraitOrientedCameraPose(cameraPose);

    WrappedAnchor target = wrappedAnchors.get(1);
    Pose targetPose = target.getAnchor().getPose();

    // First update the target pose to be on the ray between camera and target pose,
    // but 2 meters ahead of the camera pose at the closest point between the camera and
    // the target.
    // If the depth changes can we move the target point further along the same ray?

    float[] cameraPt = {0, 0, 0};
    cameraPt = cameraPose.transformPoint(cameraPt);

    // getTargetPoseAtNMeters will work if I can move the CameraPose forward along
    // its z.
    // Try to get the new camera pose in original camera coordinates to determine the z axis
    // offset. Then move the pose 2 meters ahead of that offset.
    Pose origCameraPose = wrappedAnchors.get(0).getAnchor().getPose();
    float[] newCameraPositionInOrigSpace = origCameraPose.inverse().transformPoint(cameraPt);
    targetPose = getTargetPoseAtNMeters(origCameraPose, targetPose, newCameraPositionInOrigSpace[2] - TARGET_DISTANCE_ALONG_RAY);

    long now = System.currentTimeMillis();
    if (now - lastAnchorUpdateTimestamp > 500) {
      // Update the wrapped anchor for visualization.
      // Doing this less frequently to improve performance, hopefully.
      // Note that this doesn't impact the target position, just how it is rendered.
      wrappedAnchors.get(1).getAnchor().detach();
      wrappedAnchors.remove(1);
      wrappedAnchors.add(new WrappedAnchor(session.createAnchor(targetPose), null));
      lastAnchorUpdateTimestamp = now;
    }

    // This creates the world-space coordinates of the target point.
    float[] targetPt = {0, 0, 0};
    targetPt = targetPose.transformPoint(targetPt);

    // This is where I want the camera to be *in camera coordinates*.
    // In other words, how much x, y and z from the current camera position if the current
    // is (0, 0, 0).
    float[] targetDir = cameraPose.inverse().transformPoint(targetPt);

    // Dist calculation: get the distance between the translation of the two poses.
    // This doesn't take into account the phone's orientation so it's only helpful
    // for calculating distance.
//    float xdist1 = targetPt[0] - cameraPt[0];
//    float zdist1 = targetPt[2] - cameraPt[2];
//    float dist1 = (float) (Math.sqrt(xdist1 * xdist1 + zdist1 * zdist1));
    float xdist = targetPose.tx() - cameraPose.tx();
    float zdist = targetPose.tz() - cameraPose.tz();
    float dist2 = (float) (Math.sqrt(xdist * xdist + zdist * zdist));
//    Log.d("target info", String.format("%.2f, %.2f, %.2f", xdist, zdist, dist2));

    // Try from world-space points.
    // Only need x and z coordinates (x/z plane is the plane of interest). We'll ignore y.
    // targetDir takes into account the way we are *facing* and not just the point in space.
    float tx = targetDir[0];
    float tz = targetDir[2];
    float dist3 = (float) (Math.sqrt(Math.pow(tx, 2) + Math.pow(tz, 2)));

    // tx shoots from -something to +something around the 90 degree world point. tz is steady.
    // doesn't matter if the target is in front or behind.
//    Log.d("target info", String.format("%.2f, %.2f, %.2f", tx, tz, dist3));

    double thetaFromZ = Math.acos(-tz / dist3);
    double degreesFromZ = (thetaFromZ * 360 / (2 * Math.PI)) % 360;

    float[] result = {(float)degreesFromZ, dist2, tx};
    return result;
  }

  private void sonifyCurrentOffsets(float theta, float tx) {
    if (synth == null) {
      audioManager = new AndroidAudioForJSyn();
      synth = JSyn.createSynthesizer(audioManager);
      if (voiceL == null) {
        voiceL = new SineEnvelope();
      }
      if (voiceR == null) {
        voiceR = new SineEnvelope();
      }
      synth.add(voiceL);
      synth.add(voiceR);

      // Helps with clicks.
      EnvelopeDAHDSR DAHDSR = voiceL.getDAHDSR();
      DAHDSR.hold.set(1000);
      DAHDSR.sustain.set(1000);
      DAHDSR = voiceR.getDAHDSR();
      DAHDSR.hold.set(1000);
      DAHDSR.sustain.set(1000);

      synth.add(lineOut = new LineOut());
      voiceR.output.connect(0, lineOut.input, 0);
      voiceL.output.connect(0, lineOut.input, 1);

      // Stereo audio.
      synth.start(
              SAMPLE_RATE,
              audioManager.getDefaultInputDeviceID(),
              0,
              audioManager.getDefaultOutputDeviceID(),
              2);
    }
    if (!mIsSonifying) {
      lineOut.start();
      mIsSonifying = true;
    }

    // Raw frequency
//    double freq1 = theta / 180 * (FREQ_MAX - FREQ_MIN) + FREQ_MIN;

    // Notes are easier to distinguish.
    // https://github.com/googlearchive/science-journal/blob/1be4c647b0f1e25259bf6b098cbf6416e9be9915/OpenScienceJournal/whistlepunk_library/src/main/java/com/google/android/apps/forscience/whistlepunk/audiogen/voices/ScaleVoice.java
    int minPitch = (int) Math.floor(AudioMath.frequencyToPitch(FREQ_MIN)) + 2;
    int maxPitch = (int) Math.floor(AudioMath.frequencyToPitch(FREQ_MAX));
    double freq = AudioMath.pitchToFrequency((int) (theta / 180 * (maxPitch - minPitch) + minPitch));

    if (theta < STRAIGHT_ENOUGH_THETA) {
      // Extra low note for straight enough.
      freq = FREQ_MIN;
    }

    // Set amplitude depending on L/R. It should be quieter on the further side.
    // Amplitude should be at 50% when it's fully in front of the phone or behind.

    // If it's on the left: left amplitude varies between 50 and 100%,
    // and right amplitude varies between 50 and 0%.
    double leftAmplitude = (Math.abs(90 - theta) / 90) / 2;
    if (tx > 0) {
      // It's on the right. Same but opposite.
      leftAmplitude = 1 - leftAmplitude;
    }
    // TODO: Consider slow beeping when on-target?
    // TODO: Maybe scale total amplitude (or beep frequency) by how far away you are from the target ray.
    com.softsynth.shared.time.TimeStamp timestamp = synth.createTimeStamp();
    voiceL.noteOn(freq, leftAmplitude, timestamp);
    voiceR.noteOn(freq, 1 - leftAmplitude, timestamp);
  }

  private void pauseSonification() {
    if (lineOut != null) {
      lineOut.stop();
    }
    mIsSonifying = false;
  }

  private String getMessageForCurrentPoseOffsets(float theta, float dist, float tx) {

    // Don't run this at each frame, it's too much compute.
    // Run it only every 20 ms or so.
    String message = "";

    // 0 is straight ahead
    // 180 or -180 (e.g. degreesFromX < 0) is straight behind
    // 90 is at 3, -90 is at 9 on a clock

    if (theta < STRAIGHT_ENOUGH_THETA) {
      message = "Go straight";
    } else {
      String direction = "Turn left by %.0f degrees";
      if (tx > 0) {
        direction = "Turn right by %.0f degrees";
      }

      message = String.format(direction, theta);
    }
    return message;
  }

  // Removes z-axis rotation from the Pose.
  // Doesn't modify x or y axis rotation.
  // This is equivalent to rotating the phone into portrait orientation.
  private Pose makePortraitOrientedCameraPose(Pose cameraPose) {
    // I want to left-multiply by a rotation matrix that is made from the negative z rotation
    // of the camera to get a camera that's in portrait mode.

    float[] cameraQuat = cameraPose.getRotationQuaternion();

    // https://en.wikipedia.org/wiki/Conversion_between_quaternions_and_Euler_angles
    double qx = cameraQuat[0];
    double qy = cameraQuat[1];
    double qz = cameraQuat[2];
    double qw = cameraQuat[3];

    // It seems to be normalized so I can skip this step.
//    double norm = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
//    qx /= norm;
//    qy /= norm;
//    qz /= norm;
//    qw /= norm;

    double phi = 0;  // x
    double psi = 0; // y
    double theta = 0; // z

    // Check for gimbal lock.
    // https://www.euclideanspace.com/maths/geometry/rotations/conversions/quaternionToEuler/
    double lock = qx * qy + qz * qw;
    if (lock > .499f) {
      // TODO: Should I do something here?
      Log.d("gimbal lock", "gimbal lock 1!!");
    } else if (lock < -0.499f) {
      // TODO: Should I do something here?
      Log.d("gimbal lock", "gimbal lock 2!!");
    } else {
//      phi = Math.atan2(2 * qy * qw - 2 * qx * qz, 1 - 2 * qy * qy - 2 * qz * qz);
      theta = Math.asin(2 * lock);
//      psi = Math.atan2(2 * qx * qw - 2 * qy * qz, 1 - 2 * qx * qx - 2 * qy * qy);
    }

    // Replace the camera rotation with a new rotation quaternion that's based on just phi and psi,
    // with theta = 0.
    // Do this by composing with a quaternion that inverts theta and doesn't impact phi and psi.

    psi = 0;
    phi = 0;
    theta = -1 * theta;

    double cr = Math.cos(phi * 0.5);
    double sr = Math.sin(phi * 0.5);
    double cp = Math.cos(psi * 0.5);
    double sp = Math.sin(psi * 0.5);
    double cy = Math.cos(theta * 0.5);
    double sy = Math.sin(theta * 0.5);

    qw = cr * cp * cy + sr * sp * sy;
    qx = sr * cp * cy - cr * sp * sy;
    qy = cr * sp * cy + sr * cp * sy;
    qz = cr * cp * sy - sr * sp * cy;

    Pose newRotation = Pose.makeRotation((float) qx, (float) qy, (float) qz, (float) qw);
    return cameraPose.compose(newRotation);
  }

  private Pose getTargetPoseAtNMeters(Pose cameraPose, Pose targetPose, float meters) {
    // now offset it by `meters` meters.
    float x1 = targetPose.tx() - cameraPose.tx();
    float z1 = targetPose.tz() - cameraPose.tz();

    float x2 = (float) Math.sqrt(Math.pow(meters * x1, 2) / (Math.pow(z1, 2) + Math.pow(x1, 2)));
    if (x1 < 0) {
      // Keep the sign.
      x2 *= -1;
    }
    float z2 = z1 * x2 / x1;

    return Pose.makeTranslation(x2 + cameraPose.tx(), targetPose.ty(), z2 + cameraPose.tz());
  }

  // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
  private void handleTap(Frame frame, Camera camera) {
    if (!mHasBeenClicked || camera.getTrackingState() != TrackingState.TRACKING) {
      return;
    }
    mHasBeenClicked = false;

    // Cap the number of objects created. This avoids overloading both the
    // rendering system and ARCore.

    // Clear all the wrapped anchors, start fresh.
    while (wrappedAnchors.size() > 0) {
      wrappedAnchors.get(0).getAnchor().detach();
      wrappedAnchors.remove(0);
    }

    // First create the camera's current point.
    // Extracting translation means that I lose the orientation of the camera, which is OK,
    // it makes my tracking object appear vertical.
    Pose cameraPose = camera.getDisplayOrientedPose();
    cameraPose = makePortraitOrientedCameraPose(cameraPose);
    Pose cameraTranslation = cameraPose.extractTranslation();
    wrappedAnchors.add(new WrappedAnchor(session.createAnchor(cameraPose), null));

    // Now create the target direction point.

    // Doing this creates something down the camera's Z axis, which depends on the
    // phone orientation. If the phone is in portrait mode, it's to the left horizontally.
    // So it's not with respect to the world.
//     Pose cameraRelativePose = camera.getPose().makeTranslation(0.0f, 0.0f, -1.0f);

    // This makes a world-space translation against the camera's current position.
    // I could theoretically use a hit test to decide how far away (-3 m is a bit far for my house).
    // getDisplayOrientedPose is aware of the display direction but is functionally the same for me (just rotated from getPose)
    // put it somewhere in front of the camera
    Pose targetPose = cameraPose.compose(Pose.makeTranslation(0, 0, -2.0f)).extractTranslation();
//    // bring it back down to same height as camera
    targetPose = targetPose.compose(Pose.makeTranslation(0, cameraTranslation.ty() - targetPose.ty(), 0));

    // Make sure it's two feet ahead.
    targetPose = getTargetPoseAtNMeters(cameraPose, targetPose, TARGET_DISTANCE_ALONG_RAY);

    wrappedAnchors.add(new WrappedAnchor(session.createAnchor(targetPose), null));
  }

  /**
   * Shows a pop-up dialog on the first call, determining whether the user wants to enable
   * depth-based occlusion. The result of this dialog can be retrieved with useDepthForOcclusion().
   */
  private void showOcclusionDialogIfNeeded() {
    boolean isDepthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC);
    if (!depthSettings.shouldShowDepthEnableDialog() || !isDepthSupported) {
      return; // Don't need to show dialog.
    }

    // Asks the user whether they want to use depth-based occlusion.
    new AlertDialog.Builder(this)
        .setTitle(R.string.options_title_with_depth)
        .setMessage(R.string.depth_use_explanation)
        .setPositiveButton(
            R.string.button_text_enable_depth,
            (DialogInterface dialog, int which) -> {
              depthSettings.setUseDepthForOcclusion(true);
            })
        .setNegativeButton(
            R.string.button_text_disable_depth,
            (DialogInterface dialog, int which) -> {
              depthSettings.setUseDepthForOcclusion(false);
            })
        .show();
  }

  /** Shows checkboxes to the user to facilitate toggling of depth-based effects. */
  private void launchDepthSettingsMenuDialog() {
    // Retrieves the current settings to show in the checkboxes.
    resetSettingsMenuDialogCheckboxes();

    // Shows the dialog to the user.
    Resources resources = getResources();
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
      // With depth support, the user can select visualization options.
      new AlertDialog.Builder(this)
          .setTitle(R.string.options_title_with_depth)
          .setMultiChoiceItems(
              resources.getStringArray(R.array.depth_options_array),
              depthSettingsMenuDialogCheckboxes,
              (DialogInterface dialog, int which, boolean isChecked) ->
                  depthSettingsMenuDialogCheckboxes[which] = isChecked)
          .setPositiveButton(
              R.string.done,
              (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
          .setNegativeButton(
              android.R.string.cancel,
              (DialogInterface dialog, int which) -> resetSettingsMenuDialogCheckboxes())
          .show();
    } else {
      // Without depth support, no settings are available.
      new AlertDialog.Builder(this)
          .setTitle(R.string.options_title_without_depth)
          .setPositiveButton(
              R.string.done,
              (DialogInterface dialogInterface, int which) -> applySettingsMenuDialogCheckboxes())
          .show();
    }
  }

  private void applySettingsMenuDialogCheckboxes() {
    depthSettings.setUseDepthForOcclusion(depthSettingsMenuDialogCheckboxes[0]);
    depthSettings.setDepthColorVisualizationEnabled(depthSettingsMenuDialogCheckboxes[1]);
    configureSession();
  }

  private void resetSettingsMenuDialogCheckboxes() {
    depthSettingsMenuDialogCheckboxes[0] = depthSettings.useDepthForOcclusion();
    depthSettingsMenuDialogCheckboxes[1] = depthSettings.depthColorVisualizationEnabled();
  }

  /** Checks if we detected at least one plane. */
  private boolean hasTrackingPlane() {
    for (Plane plane : session.getAllTrackables(Plane.class)) {
      if (plane.getTrackingState() == TrackingState.TRACKING) {
        return true;
      }
    }
    return false;
  }

  /** Update state based on the current frame's light estimation. */
  private void updateLightEstimation(LightEstimate lightEstimate, float[] viewMatrix) {
    if (lightEstimate.getState() != LightEstimate.State.VALID) {
      virtualObjectShader.setBool("u_LightEstimateIsValid", false);
      return;
    }
    virtualObjectShader.setBool("u_LightEstimateIsValid", true);

    Matrix.invertM(viewInverseMatrix, 0, viewMatrix, 0);
    virtualObjectShader.setMat4("u_ViewInverse", viewInverseMatrix);

    updateMainLight(
        lightEstimate.getEnvironmentalHdrMainLightDirection(),
        lightEstimate.getEnvironmentalHdrMainLightIntensity(),
        viewMatrix);
    updateSphericalHarmonicsCoefficients(
        lightEstimate.getEnvironmentalHdrAmbientSphericalHarmonics());
    cubemapFilter.update(lightEstimate.acquireEnvironmentalHdrCubeMap());
  }

  private void updateMainLight(float[] direction, float[] intensity, float[] viewMatrix) {
    // We need the direction in a vec4 with 0.0 as the final component to transform it to view space
    worldLightDirection[0] = direction[0];
    worldLightDirection[1] = direction[1];
    worldLightDirection[2] = direction[2];
    Matrix.multiplyMV(viewLightDirection, 0, viewMatrix, 0, worldLightDirection, 0);
    virtualObjectShader.setVec4("u_ViewLightDirection", viewLightDirection);
    virtualObjectShader.setVec3("u_LightIntensity", intensity);
  }

  private void updateSphericalHarmonicsCoefficients(float[] coefficients) {
    // Pre-multiply the spherical harmonics coefficients before passing them to the shader. The
    // constants in sphericalHarmonicFactors were derived from three terms:
    //
    // 1. The normalized spherical harmonics basis functions (y_lm)
    //
    // 2. The lambertian diffuse BRDF factor (1/pi)
    //
    // 3. A <cos> convolution. This is done to so that the resulting function outputs the irradiance
    // of all incoming light over a hemisphere for a given surface normal, which is what the shader
    // (environmental_hdr.frag) expects.
    //
    // You can read more details about the math here:
    // https://google.github.io/filament/Filament.html#annex/sphericalharmonics

    if (coefficients.length != 9 * 3) {
      throw new IllegalArgumentException(
          "The given coefficients array must be of length 27 (3 components per 9 coefficients");
    }

    // Apply each factor to every component of each coefficient
    for (int i = 0; i < 9 * 3; ++i) {
      sphericalHarmonicsCoefficients[i] = coefficients[i] * sphericalHarmonicFactors[i / 3];
    }
    virtualObjectShader.setVec3Array(
        "u_SphericalHarmonicsCoefficients", sphericalHarmonicsCoefficients);
  }

  /** Configures the session with feature settings. */
  private void configureSession() {
    Config config = session.getConfig();
    config.setLightEstimationMode(Config.LightEstimationMode.ENVIRONMENTAL_HDR);
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
      config.setDepthMode(Config.DepthMode.AUTOMATIC);
    } else {
      config.setDepthMode(Config.DepthMode.DISABLED);
    }
    session.configure(config);
  }
}

/**
 * Associates an Anchor with the trackable it was attached to.
 */
class WrappedAnchor {
  private Anchor anchor;
  private Trackable trackable;

  public WrappedAnchor(Anchor anchor, Trackable trackable) {
    this.anchor = anchor;
    this.trackable = trackable;
  }

  public Anchor getAnchor() {
    return anchor;
  }

  public Trackable getTrackable() {
    return trackable;
  }
}
