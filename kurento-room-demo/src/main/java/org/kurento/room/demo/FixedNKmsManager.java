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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//import java.net.URL;
//import com.amazonaws.AmazonClientException;
//import com.amazonaws.AmazonServiceException;
//import com.amazonaws.HttpMethod;
//import com.amazonaws.services.s3.AmazonS3;
//import com.amazonaws.services.s3.AmazonS3Client;
//import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import org.kurento.client.KurentoClient;
import org.kurento.jsonrpc.Session;
import org.kurento.room.exception.RoomException;
import org.kurento.room.internal.DefaultKurentoClientSessionInfo;
import org.kurento.room.kms.Kms;
import org.kurento.room.kms.KmsManager;
import org.kurento.room.kms.MaxWebRtcLoadManager;
import org.kurento.room.rpc.JsonRpcNotificationService;
import org.kurento.room.rpc.ParticipantSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * KMS manager for the room demo app.
 *
 * @author Radu Tom Vlad (rvlad@naevatec.com)
 * @since 6.0.0
 */
public class FixedNKmsManager extends KmsManager {
  private static final Logger log = LoggerFactory.getLogger(FixedNKmsManager.class);

  private String authRegex;
  private static Pattern authPattern = null;

  @Autowired
  private JsonRpcNotificationService notificationService;

  public FixedNKmsManager(List<String> kmsWsUri) {
    for (String uri : kmsWsUri) {
      this.addKms(new Kms(KurentoClient.create(uri), uri));
    }
  }

  public FixedNKmsManager(List<String> kmsWsUri, int kmsLoadLimit) {
    for (String uri : kmsWsUri) {
      Kms kms = new Kms(KurentoClient.create(uri), uri);
      kms.setLoadManager(new MaxWebRtcLoadManager(kmsLoadLimit));
      this.addKms(kms);
    }
  }

  public synchronized void setAuthRegex(String regex) {
    this.authRegex = regex != null ? regex.trim() : null;
    if (authRegex != null && !authRegex.isEmpty()) {
      authPattern = Pattern.compile(authRegex, Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);
    }
  }

  @Override
  public synchronized Kms getKms(DefaultKurentoClientSessionInfo sessionInfo) {
    String userName = null;
    String participantId = sessionInfo.getParticipantId();
    Session session = notificationService.getSession(participantId);
    if (session != null) {
      Object sessionValue = session.getAttributes().get(ParticipantSession.SESSION_KEY);
      if (sessionValue != null) {
        ParticipantSession participantSession = (ParticipantSession) sessionValue;
        userName = participantSession.getParticipantName();
      }
    }
    if (userName == null) {
      log.warn("Unable to find user name in session {}", participantId);
      throw new RoomException(RoomException.Code.ROOM_CANNOT_BE_CREATED_ERROR_CODE,
          "Not enough information");
    }
    if (!canCreateRoom(userName)) {
      throw new RoomException(RoomException.Code.ROOM_CANNOT_BE_CREATED_ERROR_CODE,
          "User cannot create a new room");
    }
    Kms kms = null;
    String type = "";
    boolean hq = isUserHQ(userName);
    if (hq) {
      kms = getLessLoadedKms();
    } else {
      kms = getNextLessLoadedKms();
      if (!kms.allowMoreElements()) {
        kms = getLessLoadedKms();
      } else {
        type = "next ";
      }
    }
    if (!kms.allowMoreElements()) {
      log.debug("Was trying Kms which has no resources left: highQ={}, "
          + "{}less loaded KMS, uri={}", hq, type, kms.getUri());
      throw new RoomException(RoomException.Code.ROOM_CANNOT_BE_CREATED_ERROR_CODE,
          "No resources left to create new room");
    }
    log.debug("Offering Kms: highQ={}, {}less loaded KMS, uri={}", hq, type, kms.getUri());
    return kms;
  }

  @Override
  public String getRecordingUrl(String roomName)
  {
//    //AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();
//    AmazonS3 s3Client = new AmazonS3Client();
//    try {
//      System.out.println("Generating pre-signed URL.");
//      java.util.Date expiration = new java.util.Date();
//      long milliSeconds = expiration.getTime();
//      milliSeconds += 1000 * 60 * 60; // Add 1 hour.
//      expiration.setTime(milliSeconds);
//
//      String bucketName = "voyager-test-wh";
//      String key = "text-object.webm";
//      GeneratePresignedUrlRequest generatePresignedUrlRequest =
//              new GeneratePresignedUrlRequest(bucketName, key);
//      generatePresignedUrlRequest.setMethod(HttpMethod.PUT);
//      generatePresignedUrlRequest.setExpiration(expiration);
//
//      URL url = s3Client.generatePresignedUrl(generatePresignedUrlRequest);
//
//      System.out.println("Pre-Signed URL = " + url.toString());
//      return url.toString();
//    } catch (AmazonServiceException exception) {
//      System.out.println("Caught an AmazonServiceException, " +
//              "which means your request made it " +
//              "to Amazon S3, but was rejected with an error response " +
//              "for some reason.");
//      System.out.println("Error Message: " + exception.getMessage());
//      System.out.println("HTTP  Code: "    + exception.getStatusCode());
//      System.out.println("AWS Error Code:" + exception.getErrorCode());
//      System.out.println("Error Type:    " + exception.getErrorType());
//      System.out.println("Request ID:    " + exception.getRequestId());
//    } catch (AmazonClientException ace) {
//      System.out.println("Caught an AmazonClientException, " +
//              "which means the client encountered " +
//              "an internal error while trying to communicate" +
//              " with S3, " +
//              "such as not being able to access the network.");
//      System.out.println("Error Message: " + ace.getMessage());
//    }
    return "file://tmp/" + roomName + ".webm";
  }

  private boolean isUserHQ(String userName) {
    return userName.toLowerCase().startsWith("special");
  }

  private boolean canCreateRoom(String userName) {
    if (authPattern == null) {
      return true;
    }
    Matcher m = authPattern.matcher(userName);
    return m.matches();
  }
}
