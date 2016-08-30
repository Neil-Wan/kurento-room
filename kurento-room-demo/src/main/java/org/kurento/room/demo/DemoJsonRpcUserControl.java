/*
 * (C) Copyright 2015 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kurento.room.demo;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.*;
import org.kurento.commons.exception.KurentoException;
import org.kurento.jsonrpc.Transaction;
import org.kurento.jsonrpc.message.Request;
import org.kurento.room.NotificationRoomManager;
import org.kurento.room.api.pojo.ParticipantRequest;
import org.kurento.room.kms.KmsManager;
import org.kurento.room.rpc.JsonRpcNotificationService;
import org.kurento.room.rpc.JsonRpcUserControl;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

/**
 * User control that applies a face overlay filter when publishing video.
 *
 * @author Radu Tom Vlad (rvlad@naevatec.com)
 */
public class DemoJsonRpcUserControl extends JsonRpcUserControl {

  private static final String SESSION_ATTRIBUTE_HAT_FILTER = "hatFilter";

  private static final String CUSTOM_REQUEST_HAT_PARAM = "hat";

  private static final Logger log = LoggerFactory.getLogger(DemoJsonRpcUserControl.class);

  private KmsManager kmsManager;
  private final ConcurrentHashMap<String, UserSession> users = new ConcurrentHashMap<>();

  private String hatUrl;

  private final JsonRpcNotificationService notificationService;

  private float offsetXPercent;
  private float offsetYPercent;
  private float widthPercent;
  private float heightPercent;

  public DemoJsonRpcUserControl(NotificationRoomManager roomManager
                                , JsonRpcNotificationService service
          , KmsManager kmsManager) {
    super(roomManager);
    notificationService = service;
    this.kmsManager = kmsManager;
  }

  public void setHatUrl(String hatUrl) {
    this.hatUrl = hatUrl;
    log.info("Hat URL: {}", hatUrl);
  }

  public void setHatCoords(JsonObject hatCoords) {
    if (hatCoords.get("offsetXPercent") != null) {
      offsetXPercent = hatCoords.get("offsetXPercent").getAsFloat();
    }
    if (hatCoords.get("offsetYPercent") != null) {
      offsetYPercent = hatCoords.get("offsetYPercent").getAsFloat();
    }
    if (hatCoords.get("widthPercent") != null) {
      widthPercent = hatCoords.get("widthPercent").getAsFloat();
    }
    if (hatCoords.get("heightPercent") != null) {
      heightPercent = hatCoords.get("heightPercent").getAsFloat();
    }
    log.info("Hat coords:\n\toffsetXPercent = {}\n\toffsetYPercent = {}"
        + "\n\twidthPercent = {}\n\theightPercent = {}", offsetXPercent, offsetYPercent,
        widthPercent, heightPercent);
  }

  private void onIceCandidate(Request<JsonObject> request, ParticipantRequest participantRequest) {
    UserSession user = users.get(participantRequest.getParticipantId());

    if (user != null) {
      JsonObject jsonCandidate = request.getParams().get("candidate").getAsJsonObject();
      IceCandidate candidate = new IceCandidate(jsonCandidate.get("candidate").getAsString(),
              jsonCandidate.get("sdpMid").getAsString(), jsonCandidate.get("sdpMLineIndex").getAsInt());
      user.getWebRtcEndpoint().addIceCandidate(candidate);
    }
  }

  private void start(Request<JsonObject> request, ParticipantRequest participantRequest) {
    // 1. Media pipeline
    final UserSession user = new UserSession();
    MediaPipeline pipeline = kmsManager.getLessLoadedKms().getKurentoClient().createMediaPipeline();
    user.setMediaPipeline(pipeline);
    WebRtcEndpoint webRtcEndpoint = new WebRtcEndpoint.Builder(pipeline).build();
    user.setWebRtcEndpoint(webRtcEndpoint);
    String videourl = request.getParams().get("videourl").getAsString();
    final PlayerEndpoint playerEndpoint = new PlayerEndpoint.Builder(pipeline, videourl).build();
    user.setPlayerEndpoint(playerEndpoint);
    users.put(participantRequest.getParticipantId(), user);

    playerEndpoint.connect(webRtcEndpoint);

    // 2. WebRtcEndpoint
    // ICE candidates
    webRtcEndpoint.addOnIceCandidateListener(new EventListener<OnIceCandidateEvent>() {
      @Override
      public void onEvent(OnIceCandidateEvent event) {
        JsonObject response = new JsonObject();
        response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
        try {
          synchronized (participantRequest) {
            notificationService.sendNotification(participantRequest.getParticipantId(), "iceCandidate", response);
          }
        } catch (Exception e) {
          log.debug(e.getMessage());
        }
      }
    });
    JsonObject response = new JsonObject();
    String sdpOffer = request.getParams().get("sdpOffer").getAsString();
    String sdpAnswer = webRtcEndpoint.processOffer(sdpOffer);
    response.addProperty("sdpAnswer", sdpAnswer);
    notificationService.sendNotification(participantRequest.getParticipantId(), "startResponse", response);

    webRtcEndpoint.addMediaStateChangedListener(new EventListener<MediaStateChangedEvent>() {
      @Override
      public void onEvent(MediaStateChangedEvent event) {

        if (event.getNewState() == MediaState.CONNECTED) {
          VideoInfo videoInfo = playerEndpoint.getVideoInfo();

          JsonObject response = new JsonObject();
          response.addProperty("isSeekable", videoInfo.getIsSeekable());
          response.addProperty("initSeekable", videoInfo.getSeekableInit());
          response.addProperty("endSeekable", videoInfo.getSeekableEnd());
          response.addProperty("videoDuration", videoInfo.getDuration());
          notificationService.sendNotification(participantRequest.getParticipantId(), "videoInfo", response);
        }
      }
    });

    webRtcEndpoint.gatherCandidates();

    // 3. PlayEndpoint
    playerEndpoint.addErrorListener(new EventListener<ErrorEvent>() {
      @Override
      public void onEvent(ErrorEvent event) {
        log.info("ErrorEvent: {}", event.getDescription());
        notificationService.sendNotification(participantRequest.getParticipantId(), "playEnd", new JsonObject());
      }
    });

    playerEndpoint.addEndOfStreamListener(new EventListener<EndOfStreamEvent>() {
      @Override
      public void onEvent(EndOfStreamEvent event) {
        log.info("EndOfStreamEvent: {}", event.getTimestamp());
        notificationService.sendNotification(participantRequest.getParticipantId(), "playEnd", new JsonObject());
      }
    });
  }

  private void play(Request<JsonObject> request, ParticipantRequest participantRequest) {
    UserSession user = users.get(participantRequest.getParticipantId());

    if (user != null) {
      user.getPlayerEndpoint().play();
    }
  }

  private void pause(Request<JsonObject> request, ParticipantRequest participantRequest) {
    UserSession user = users.get(participantRequest.getParticipantId());

    if (user != null) {
      user.getPlayerEndpoint().pause();
    }
  }

  private void doSeek(Request<JsonObject> request, ParticipantRequest participantRequest) {
    UserSession user = users.get(participantRequest.getParticipantId());

    if (user != null) {
      try {
        user.getPlayerEndpoint().setPosition(request.getParams().get("position").getAsLong());
      } catch (KurentoException e) {
        log.debug("The seek cannot be performed");
        JsonObject response = new JsonObject();
        response.addProperty("message", "Seek failed");
        notificationService.sendNotification(participantRequest.getParticipantId(), "seek", response);
      }
    }
  }

  private void stop(Request<JsonObject> request, ParticipantRequest participantRequest) {
    UserSession user = users.remove(participantRequest.getParticipantId());

    if (user != null) {
      user.release();
    }
  }

  @Override
  public void customRequest(Transaction transaction, Request<JsonObject> request,
      ParticipantRequest participantRequest) {
    try {
      String sessionId = request.getParams().get("id").getAsString();
      switch (sessionId) {
        case "onIceCandidate":
          onIceCandidate(request, participantRequest);
          break;
        case "start":
          start(request, participantRequest);
          break;
        case "play":
          play(request, participantRequest);
          break;
        case "pause":
          pause(request, participantRequest);
          break;
        case "doSeek":
          doSeek(request, participantRequest);
          break;
        case "stop":
          stop(request, participantRequest);
          break;
      }
      transaction.sendResponse(new JsonObject());
//      if (request.getParams() == null || request.getParams().get(CUSTOM_REQUEST_HAT_PARAM) == null) {
//        throw new RuntimeException("Request element '" + CUSTOM_REQUEST_HAT_PARAM + "' is missing");
//      }
//      boolean hatOn = request.getParams().get(CUSTOM_REQUEST_HAT_PARAM).getAsBoolean();
//      String pid = participantRequest.getParticipantId();
//      if (hatOn) {
//        if (transaction.getSession().getAttributes().containsKey(SESSION_ATTRIBUTE_HAT_FILTER)) {
//          throw new RuntimeException("Hat filter already on");
//        }
//        log.info("Applying face overlay filter to session {}", pid);
//        FaceOverlayFilter faceOverlayFilter = new FaceOverlayFilter.Builder(
//            roomManager.getPipeline(pid)).build();
//        faceOverlayFilter.setOverlayedImage(this.hatUrl, this.offsetXPercent, this.offsetYPercent,
//            this.widthPercent, this.heightPercent);
//        roomManager.addMediaElement(pid, faceOverlayFilter);
//        transaction.getSession().getAttributes()
//        .put(SESSION_ATTRIBUTE_HAT_FILTER, faceOverlayFilter);
//      } else {
//        if (!transaction.getSession().getAttributes().containsKey(SESSION_ATTRIBUTE_HAT_FILTER)) {
//          throw new RuntimeException("This user has no hat filter yet");
//        }
//        log.info("Removing face overlay filter from session {}", pid);
//        roomManager.removeMediaElement(pid, (MediaElement) transaction.getSession().getAttributes()
//            .get(SESSION_ATTRIBUTE_HAT_FILTER));
//        transaction.getSession().getAttributes().remove(SESSION_ATTRIBUTE_HAT_FILTER);
//      }
//      transaction.sendResponse(new JsonObject());
    } catch (Exception e) {
      log.error("Unable to handle custom request", e);
      try {
        transaction.sendError(e);
      } catch (IOException e1) {
        log.warn("Unable to send error response", e1);
      }
    }
  }
}
