/*
 * Copyright (C) 2024 Yubico.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yubico.yubikit.fido.ctap;

import com.yubico.yubikit.core.application.CommandException;
import com.yubico.yubikit.core.fido.CtapException;
import com.yubico.yubikit.core.internal.codec.Base64;
import com.yubico.yubikit.fido.Cbor;

import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

@SuppressWarnings("unused")
public class FingerprintBioEnrollment extends BioEnrollment {

    /* commands */
    private static final int CMD_ENROLL_BEGIN = 0x01;
    private static final int CMD_ENROLL_CAPTURE_NEXT = 0x02;
    private static final int CMD_ENROLL_CANCEL = 0x03;
    private static final int CMD_ENUMERATE_ENROLLMENTS = 0x04;
    private static final int CMD_SET_NAME = 0x05;
    private static final int CMD_REMOVE_ENROLLMENT = 0x06;
    private static final int CMD_GET_SENSOR_INFO = 0x07;

    /* parameters */
    private static final int PARAM_TEMPLATE_ID = 0x01;
    private static final int PARAM_TEMPLATE_FRIENDLY_NAME = 0x02;
    private static final int PARAM_TIMEOUT_MS = 0x03;

    /* feedback */
    public static final int FEEDBACK_FP_GOOD = 0x00;
    public static final int FEEDBACK_FP_TOO_HIGH = 0x01;
    public static final int FEEDBACK_FP_TOO_LOW = 0x02;
    public static final int FEEDBACK_FP_TOO_LEFT = 0x03;
    public static final int FEEDBACK_FP_TOO_RIGHT = 0x04;
    public static final int FEEDBACK_FP_TOO_FAST = 0x05;
    public static final int FEEDBACK_FP_TOO_SLOW = 0x06;
    public static final int FEEDBACK_FP_POOR_QUALITY = 0x07;
    public static final int FEEDBACK_FP_TOO_SKEWED = 0x08;
    public static final int FEEDBACK_FP_TOO_SHORT = 0x09;
    public static final int FEEDBACK_FP_MERGE_FAILURE = 0x0A;
    public static final int FEEDBACK_FP_EXISTS = 0x0B;
    // 0x0C not used
    public static final int FEEDBACK_NO_USER_ACTIVITY = 0x0D;
    public static final int FEEDBACK_NO_UP_TRANSITION = 0x0E;

    private final PinUvAuthProtocol pinUvAuth;
    private final byte[] pinUvToken;
    private final org.slf4j.Logger logger = LoggerFactory.getLogger(FingerprintBioEnrollment.class);

    public static class CaptureError extends Exception {
        private final int code;

        public CaptureError(int code) {
            super("Fingerprint capture error: " + code);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }

    static class CaptureStatus {
        private final int sampleStatus;
        private final int remaining;

        public CaptureStatus(int sampleStatus, int remaining) {
            this.sampleStatus = sampleStatus;
            this.remaining = remaining;
        }

        public int getSampleStatus() {
            return sampleStatus;
        }

        public int getRemaining() {
            return remaining;
        }
    }

    static class EnrollBeginStatus extends CaptureStatus {
        private final byte[] templateId;

        public EnrollBeginStatus(byte[] templateId, int sampleStatus, int remaining) {
            super(sampleStatus, remaining);
            this.templateId = templateId;
        }

        public byte[] getTemplateId() {
            return templateId;
        }
    }

    public static class Context {
        private final FingerprintBioEnrollment bioEnrollment;
        @Nullable
        private final Integer timeout;
        @Nullable
        private byte[] templateId;
        @Nullable
        private Integer remaining;

        public Context(
                FingerprintBioEnrollment bioEnrollment,
                @Nullable Integer timeout,
                @Nullable byte[] templateId,
                @Nullable Integer remaining) {
            this.bioEnrollment = bioEnrollment;
            this.timeout = timeout;
            this.templateId = templateId;
            this.remaining = remaining;
        }

        /**
         * Capture a fingerprint sample.
         * <p>
         * This call will block for up to timeout milliseconds (or indefinitely, if
         * timeout not specified) waiting for the user to scan their fingerprint to
         * collect one sample.
         *
         * @return None, if more samples are needed, or the template ID if enrollment is
         * completed.
         * @throws IOException      A communication error in the transport layer.
         * @throws CommandException A communication in the protocol layer.
         * @throws CaptureError     An error during fingerprint capture.
         */
        @Nullable
        public byte[] capture() throws IOException, CommandException, CaptureError {
            int sampleStatus;
            if (templateId == null) {
                final EnrollBeginStatus status = bioEnrollment.enrollBegin(timeout);
                templateId = status.getTemplateId();
                remaining = status.getRemaining();
                sampleStatus = status.getSampleStatus();
            } else {
                final CaptureStatus status = bioEnrollment.enrollCaptureNext(templateId, timeout);
                remaining = status.getRemaining();
                sampleStatus = status.getSampleStatus();
            }

            if (sampleStatus != FEEDBACK_FP_GOOD) {
                throw new CaptureError(sampleStatus);
            }

            if (remaining == 0) {
                return templateId;
            }

            return null;
        }

        /**
         * Cancels ongoing enrollment.
         *
         * @throws IOException      A communication error in the transport layer.
         * @throws CommandException A communication in the protocol layer.
         */
        public void cancel() throws IOException, CommandException {
            bioEnrollment.enrollCancel();
            templateId = null;
        }
    }

    public FingerprintBioEnrollment(
            Ctap2Session ctap,
            PinUvAuthProtocol pinUvAuthProtocol,
            byte[] pinUvToken) throws IOException, CommandException {
        super(ctap, BioEnrollment.MODALITY_FINGERPRINT);
        this.pinUvAuth = pinUvAuthProtocol;
        this.pinUvToken = pinUvToken;
    }

    private Map<Integer, ?> call(
            Integer subCommand,
            @Nullable Map<?, ?> subCommandParams) throws IOException, CommandException {
        return call(subCommand, subCommandParams, true);
    }

    private Map<Integer, ?> call(
            Integer subCommand,
            @Nullable Map<?, ?> subCommandParams,
            boolean authenticate) throws IOException, CommandException {
        byte[] pinUvAuthParam = null;
        if (authenticate) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(MODALITY_FINGERPRINT);
            output.write(subCommand);
            if (subCommandParams != null) {
                Cbor.encodeTo(output, subCommandParams);
            }
            pinUvAuthParam = pinUvAuth.authenticate(pinUvToken, output.toByteArray());
        }

        return ctap.bioEnrollment(
                modality,
                subCommand,
                subCommandParams,
                pinUvAuth.getVersion(),
                pinUvAuthParam,
                null);
    }

    /**
     * Get fingerprint sensor info.
     *
     * @return A dict containing FINGERPRINT_KIND, MAX_SAMPLES_REQUIRES and
     * MAX_TEMPLATE_FRIENDLY_NAME.
     * @throws IOException      A communication error in the transport layer.
     * @throws CommandException A communication in the protocol layer.
     */
    public Map<Integer, ?> getFingerprintSensorInfo() throws IOException, CommandException {
        return call(CMD_GET_SENSOR_INFO, null, false);
    }

    /**
     * Start fingerprint enrollment.
     * <p>
     * Starts the process of enrolling a new fingerprint, and will wait for the user
     * to scan their fingerprint once to provide an initial sample.
     *
     * @param timeout Optional timeout in milliseconds.
     * @return A status object containing the new template ID, the sample status,
     * and the number of samples remaining to complete the enrollment.
     */
    public EnrollBeginStatus enrollBegin(@Nullable Integer timeout)
            throws IOException, CommandException {
        logger.debug("Starting fingerprint enrollment");

        Map<Integer, Object> parameters = new HashMap<>();
        if (timeout != null) parameters.put(PARAM_TIMEOUT_MS, timeout);

        final Map<Integer, ?> result = call(CMD_ENROLL_BEGIN, parameters);
        logger.debug("Sample capture result: {}", result);
        return new EnrollBeginStatus(
                Objects.requireNonNull((byte[]) result.get(RESULT_TEMPLATE_ID)),
                Objects.requireNonNull((Integer) result.get(RESULT_LAST_SAMPLE_STATUS)),
                Objects.requireNonNull((Integer) result.get(RESULT_REMAINING_SAMPLES)));
    }

    /**
     * Continue fingerprint enrollment.
     * <p>
     * Continues enrolling a new fingerprint and will wait for the user to scan their
     * fingerprint once to provide a new sample.
     * Once the number of samples remaining is 0, the enrollment is completed.
     *
     * @param templateId The template ID returned by a call to {@link #enrollBegin(Integer timeout)}.
     * @param timeout    Optional timeout in milliseconds.
     * @return A status object containing the sample status, and the number of samples
     * remaining to complete the enrollment.
     */
    public CaptureStatus enrollCaptureNext(
            byte[] templateId,
            @Nullable Integer timeout) throws IOException, CommandException {
        logger.debug("Capturing next sample with (timeout={})", timeout);

        Map<Integer, Object> parameters = new HashMap<>();
        parameters.put(PARAM_TEMPLATE_ID, templateId);
        if (timeout != null) parameters.put(PARAM_TIMEOUT_MS, timeout);

        final Map<Integer, ?> result = call(CMD_ENROLL_CAPTURE_NEXT, parameters);
        logger.debug("Sample capture result: {}", result);
        return new CaptureStatus(
                Objects.requireNonNull((Integer) result.get(RESULT_LAST_SAMPLE_STATUS)),
                Objects.requireNonNull((Integer) result.get(RESULT_REMAINING_SAMPLES)));
    }

    /**
     * Cancel any ongoing fingerprint enrollment.
     */
    public void enrollCancel() throws IOException, CommandException {
        logger.debug("Cancelling fingerprint enrollment.");
        call(CMD_ENROLL_CANCEL, null, false);
    }

    /**
     * Convenience wrapper for doing fingerprint enrollment.
     * <p>
     * See FingerprintEnrollmentContext for details.
     *
     * @param timeout Optional timeout in milliseconds.
     * @return An initialized FingerprintEnrollmentContext.
     */
    public Context enroll(@Nullable Integer timeout) {
        return new Context(this, timeout, null, null);
    }

    /**
     * Get a dict of enrolled fingerprint templates which maps template ID's to
     * their friendly names.
     *
     * @return A Map of enrolled templateId -> name pairs.
     */
    public Map<byte[], String> enumerateEnrollments() throws IOException, CommandException {
        try {
            final Map<Integer, ?> result = call(CMD_ENUMERATE_ENROLLMENTS, null);

            @SuppressWarnings("unchecked")
            final List<Map<Integer, ?>> infos = (List<Map<Integer, ?>>) result.get(RESULT_TEMPLATE_INFOS);
            final Map<byte[], String> retval = new HashMap<>();
            for (Map<Integer, ?> info : infos) {
                final byte[] templateId =
                        Objects.requireNonNull((byte[]) info.get(TEMPLATE_INFO_ID));
                final String templateFriendlyName = (String) info.get(TEMPLATE_INFO_NAME);
                retval.put(templateId, templateFriendlyName);
            }

            logger.debug("Enumerated enrollments: {}", retval);

            return retval;
        } catch (CtapException e) {
            if (e.getCtapError() == CtapException.ERR_INVALID_OPTION) {
                return Collections.emptyMap();
            }
            throw e;
        }
    }

    /**
     * Set/Change the friendly name of a previously enrolled fingerprint template.
     *
     * @param templateId The ID of the template to change.
     * @param name       A friendly name to give the template.
     */
    public void setName(byte[] templateId, String name) throws IOException, CommandException {
        logger.debug("Changing name of template: {} {}", Base64.toUrlSafeString(templateId), name);

        Map<Integer, Object> parameters = new HashMap<>();
        parameters.put(TEMPLATE_INFO_ID, templateId);
        parameters.put(TEMPLATE_INFO_NAME, name);

        call(CMD_SET_NAME, parameters);
        logger.info("Fingerprint template renamed");
    }

    /**
     * Remove a previously enrolled fingerprint template.
     *
     * @param templateId The Id of the template to remove.
     */
    public void removeEnrollment(byte[] templateId) throws IOException, CommandException {
        logger.debug("Deleting template: {}", Base64.toUrlSafeString(templateId));

        Map<Integer, Object> parameters = new HashMap<>();
        parameters.put(TEMPLATE_INFO_ID, templateId);

        call(CMD_REMOVE_ENROLLMENT, parameters);
        logger.info("Fingerprint template deleted");
    }
}
