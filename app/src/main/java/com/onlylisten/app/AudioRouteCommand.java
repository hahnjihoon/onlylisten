package com.onlylisten.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Looper;
import android.os.Process;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class AudioRouteCommand {
    // Hidden system APIs are used only by the user-authorized adb shell process.
    @SuppressLint({"PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi"})
    public static void main(String[] args) {
        JSONObject result = new JSONObject();
        AudioManager audio = null;
        Object strategy = null;
        Method writeInput = null;
        Method clearInput = null;
        Method writeOutput = null;
        Method clearOutput = null;
        List<Object> originalInput = new ArrayList<>();
        List<Object> originalOutput = new ArrayList<>();
        String action = args.length == 0 ? "status" : args[0];
        boolean changed = false;
        String step = "권한 확인";
        try {
            if (Process.myUid() != 2000 && Process.myUid() != 0) {
                throw new SecurityException("무선 디버깅 연결을 먼저 완료해 주세요.");
            }
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            Context shell = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
            if (shell.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING")
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("이 안드로이드 버전은 디버깅 권한에 오디오 경로 변경 권한을 제공하지 않습니다.");
            }
            audio = shell.getSystemService(AudioManager.class);
            if (audio == null) {
                throw new IllegalStateException("오디오 시스템에 연결하지 못했습니다.");
            }
            result.put("androidApi", android.os.Build.VERSION.SDK_INT);
            result.put("mode", audio.getMode());
            result.put("permissionGranted", true);

            step = "통화 경로 조회";
            Class<?> deviceClass = Class.forName("android.media.AudioDeviceAttributes");
            Class<?> strategyClass = Class.forName("android.media.audiopolicy.AudioProductStrategy");
            List<?> strategies;
            try {
                strategies = (List<?>) AudioManager.class.getMethod("getAudioProductStrategies").invoke(null);
            } catch (NoSuchMethodException unavailable) {
                strategies = (List<?>) strategyClass.getMethod("getAudioProductStrategies").invoke(null);
            }
            AudioAttributes voice = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            for (Object candidate : strategies) {
                AudioAttributes legacy = (AudioAttributes) strategyClass
                        .getMethod("getAudioAttributesForLegacyStreamType", int.class)
                        .invoke(candidate, AudioManager.STREAM_VOICE_CALL);
                if (legacy != null) {
                    strategy = candidate;
                    break;
                }
            }
            if (strategy == null) {
                for (Object candidate : strategies) {
                    if ((Boolean) strategyClass.getMethod("supportsAudioAttributes", AudioAttributes.class)
                            .invoke(candidate, voice)) {
                        strategy = candidate;
                        break;
                    }
                }
            }
            if (strategy == null) {
                throw new IllegalStateException("이 기기의 전화 통화 오디오 경로를 찾지 못했습니다.");
            }
            int strategyId = (Integer) strategyClass.getMethod("getId").invoke(strategy);
            Method readInput = AudioManager.class.getMethod("getPreferredDevicesForCapturePreset", int.class);
            Method readOutput = AudioManager.class.getMethod("getPreferredDevicesForStrategy", strategyClass);
            writeInput = AudioManager.class.getMethod("setPreferredDeviceForCapturePreset", int.class, deviceClass);
            clearInput = AudioManager.class.getMethod("clearPreferredDevicesForCapturePreset", int.class);
            writeOutput = AudioManager.class.getMethod("setPreferredDevicesForStrategy", strategyClass, List.class);
            clearOutput = AudioManager.class.getMethod("removePreferredDeviceForStrategy", strategyClass);
            List<?> inputPreferences = (List<?>) readInput.invoke(audio, MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            List<?> outputPreferences = (List<?>) readOutput.invoke(audio, strategy);

            JSONObject snapshot = new JSONObject().put("strategyId", strategyId);
            String[] snapshotNames = {"input", "output"};
            List<?>[] preferences = {inputPreferences, outputPreferences};
            for (int index = 0; index < preferences.length; index++) {
                JSONArray entries = new JSONArray();
                for (Object device : preferences[index]) {
                    entries.put(new JSONObject()
                            .put("role", deviceClass.getMethod("getRole").invoke(device))
                            .put("type", deviceClass.getMethod("getType").invoke(device))
                            .put("address", deviceClass.getMethod("getAddress").invoke(device)));
                }
                snapshot.put(snapshotNames[index], entries);
            }
            result.put("snapshot", snapshot);

            if (action.equals("on") || action.equals("off")) {
                if (args.length < 2 || args[1].length() > 32768) {
                    throw new IllegalArgumentException("원래 오디오 설정의 복원 정보가 없습니다.");
                }
                JSONObject backup = new JSONObject(new String(
                        Base64.decode(args[1], Base64.NO_WRAP), StandardCharsets.UTF_8));
                if (backup.getInt("strategyId") != strategyId) {
                    throw new IllegalStateException("통화 오디오 경로가 변경되어 저장된 설정을 적용할 수 없습니다.");
                }
                for (int index = 0; index < snapshotNames.length; index++) {
                    JSONArray entries = backup.getJSONArray(snapshotNames[index]);
                    if (entries.length() > (index == 0 ? 1 : 16)) {
                        throw new IllegalArgumentException("지원하지 않는 원래 오디오 설정입니다.");
                    }
                    for (int entry = 0; entry < entries.length(); entry++) {
                        JSONObject device = entries.getJSONObject(entry);
                        Object restored = deviceClass.getConstructor(int.class, int.class, String.class)
                                .newInstance(device.getInt("role"), device.getInt("type"), device.getString("address"));
                        if (index == 0) {
                            originalInput.add(restored);
                        } else {
                            originalOutput.add(restored);
                        }
                    }
                }
                step = action.equals("on") ? "휴대폰 마이크 지정" : "원래 통화 설정 복원";
                if (action.equals("on")) {
                    AudioDeviceInfo phoneMic = null;
                    for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                        if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                            phoneMic = device;
                            break;
                        }
                    }
                    AudioDeviceInfo bluetooth = audio.getCommunicationDevice();
                    if (bluetooth == null || (bluetooth.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            && bluetooth.getType() != AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                        bluetooth = null;
                        for (AudioDeviceInfo device : audio.getAvailableCommunicationDevices()) {
                            if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                                    || device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                                bluetooth = device;
                                if (device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                                    break;
                                }
                            }
                        }
                    }
                    if (phoneMic == null) {
                        throw new IllegalStateException("휴대폰 내장 마이크를 찾지 못했습니다.");
                    }
                    if (bluetooth == null) {
                        throw new IllegalStateException("통화용 이어폰이 연결되지 않았습니다. 블루투스 설정에서 이어폰의 통화 연결을 켜 주세요.");
                    }
                    Object input = deviceClass.getConstructor(AudioDeviceInfo.class).newInstance(phoneMic);
                    Object output = deviceClass.getConstructor(AudioDeviceInfo.class).newInstance(bluetooth);
                    changed = true;
                    boolean outputAccepted = (Boolean) writeOutput.invoke(audio, strategy, java.util.Collections.singletonList(output));
                    if (!outputAccepted) {
                        throw new IllegalStateException("휴대폰이 이어폰 통화 출력 지정을 거부했습니다.");
                    }
                    boolean inputAccepted = (Boolean) writeInput.invoke(audio, MediaRecorder.AudioSource.VOICE_COMMUNICATION, input);
                    if (!inputAccepted) {
                        throw new IllegalStateException("휴대폰이 내장 마이크 지정을 거부했습니다.");
                    }
                    result.put("earphone", bluetooth.getProductName().toString());
                } else {
                    changed = true;
                    boolean outputRestored;
                    if (originalOutput.isEmpty()) {
                        clearOutput.invoke(audio, strategy);
                        outputRestored = ((List<?>) readOutput.invoke(audio, strategy)).isEmpty();
                    } else {
                        outputRestored = (Boolean) writeOutput.invoke(audio, strategy, originalOutput);
                    }
                    boolean inputRestored;
                    if (originalInput.isEmpty()) {
                        clearInput.invoke(audio, MediaRecorder.AudioSource.VOICE_COMMUNICATION);
                        inputRestored = ((List<?>) readInput.invoke(audio, MediaRecorder.AudioSource.VOICE_COMMUNICATION)).isEmpty();
                    } else {
                        inputRestored = (Boolean) writeInput.invoke(audio, MediaRecorder.AudioSource.VOICE_COMMUNICATION, originalInput.get(0));
                    }
                    if (!inputRestored || !outputRestored) {
                        throw new IllegalStateException("원래 설정을 모두 복원하지 못했습니다. 연결을 확인한 뒤 다시 OFF를 눌러 주세요.");
                    }
                }
                inputPreferences = (List<?>) readInput.invoke(audio, MediaRecorder.AudioSource.VOICE_COMMUNICATION);
                outputPreferences = (List<?>) readOutput.invoke(audio, strategy);
                if (action.equals("on")) {
                    boolean phoneSelected = inputPreferences.size() == 1
                            && (Integer) deviceClass.getMethod("getType").invoke(inputPreferences.get(0))
                            == AudioDeviceInfo.TYPE_BUILTIN_MIC;
                    boolean bluetoothSelected = false;
                    for (Object output : outputPreferences) {
                        int type = (Integer) deviceClass.getMethod("getType").invoke(output);
                        bluetoothSelected |= type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET;
                    }
                    if (!phoneSelected || !bluetoothSelected) {
                        throw new IllegalStateException("오디오 시스템이 요청한 마이크·이어폰 설정을 유지하지 않았습니다.");
                    }
                    result.put("enabled", true);
                } else {
                    List<?>[] actualLists = {inputPreferences, outputPreferences};
                    List<?>[] expectedLists = {originalInput, originalOutput};
                    for (int group = 0; group < actualLists.length; group++) {
                        if (actualLists[group].size() != expectedLists[group].size()) {
                            throw new IllegalStateException("원래 오디오 설정으로 복원됐는지 확인하지 못했습니다.");
                        }
                        for (Object expected : expectedLists[group]) {
                            boolean matched = false;
                            for (Object actual : actualLists[group]) {
                                matched |= deviceClass.getMethod("getRole").invoke(actual).equals(deviceClass.getMethod("getRole").invoke(expected))
                                        && deviceClass.getMethod("getType").invoke(actual).equals(deviceClass.getMethod("getType").invoke(expected))
                                        && deviceClass.getMethod("getAddress").invoke(actual).equals(deviceClass.getMethod("getAddress").invoke(expected));
                            }
                            if (!matched) {
                                throw new IllegalStateException("원래 오디오 장치로 복원됐는지 확인하지 못했습니다.");
                            }
                        }
                    }
                    result.put("enabled", false);
                }
            }

            step = "오디오 경로 확인";
            boolean inputPreferred = inputPreferences.size() == 1
                    && (Integer) deviceClass.getMethod("getType").invoke(inputPreferences.get(0))
                    == AudioDeviceInfo.TYPE_BUILTIN_MIC;
            result.put("phoneMicPreferred", inputPreferred);
            JSONArray selectedOutput = new JSONArray();
            try {
                List<?> selected = (List<?>) AudioManager.class.getMethod("getDevicesForAttributes", AudioAttributes.class).invoke(audio, voice);
                for (Object device : selected) {
                    selectedOutput.put(new JSONObject()
                            .put("type", deviceClass.getMethod("getType").invoke(device))
                            .put("address", deviceClass.getMethod("getAddress").invoke(device)));
                }
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                result.put("outputQueryUnavailable", true);
            }
            result.put("selectedOutput", selectedOutput);
            try {
                AudioAttributes.Builder captureBuilder = new AudioAttributes.Builder();
                AudioAttributes.Builder.class.getMethod("setCapturePreset", int.class)
                        .invoke(captureBuilder, MediaRecorder.AudioSource.VOICE_COMMUNICATION);
                List<?> selected = (List<?>) AudioManager.class.getMethod("getDevicesForAttributes", AudioAttributes.class)
                        .invoke(audio, captureBuilder.build());
                for (Object device : selected) {
                    int role = (Integer) deviceClass.getMethod("getRole").invoke(device);
                    if (role == 1) {
                        result.put("selectedInputType", deviceClass.getMethod("getType").invoke(device));
                        break;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                result.put("inputQueryUnavailable", true);
            }
            result.put("ok", true);
            result.put("step", "완료");
        } catch (Throwable error) {
            Throwable cause = error;
            while (cause instanceof InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            boolean rolledBack = false;
            if (changed && action.equals("on") && audio != null) {
                try {
                    if (originalOutput.isEmpty()) {
                        clearOutput.invoke(audio, strategy);
                    } else if (!(Boolean) writeOutput.invoke(audio, strategy, originalOutput)) {
                        throw new IllegalStateException("통화 출력 복원 실패");
                    }
                    if (originalInput.isEmpty()) {
                        clearInput.invoke(audio, MediaRecorder.AudioSource.VOICE_COMMUNICATION);
                    } else if (!(Boolean) writeInput.invoke(audio, MediaRecorder.AudioSource.VOICE_COMMUNICATION, originalInput.get(0))) {
                        throw new IllegalStateException("마이크 복원 실패");
                    }
                    Class<?> deviceClass = Class.forName("android.media.AudioDeviceAttributes");
                    Class<?> strategyClass = Class.forName("android.media.audiopolicy.AudioProductStrategy");
                    List<?>[] actualLists = {
                            (List<?>) AudioManager.class.getMethod("getPreferredDevicesForCapturePreset", int.class)
                                    .invoke(audio, MediaRecorder.AudioSource.VOICE_COMMUNICATION),
                            (List<?>) AudioManager.class.getMethod("getPreferredDevicesForStrategy", strategyClass)
                                    .invoke(audio, strategy)
                    };
                    List<?>[] expectedLists = {originalInput, originalOutput};
                    for (int group = 0; group < actualLists.length; group++) {
                        if (actualLists[group].size() != expectedLists[group].size()) {
                            throw new IllegalStateException("복원된 장치 수 불일치");
                        }
                        for (Object expected : expectedLists[group]) {
                            boolean matched = false;
                            for (Object actual : actualLists[group]) {
                                matched |= deviceClass.getMethod("getRole").invoke(actual).equals(deviceClass.getMethod("getRole").invoke(expected))
                                        && deviceClass.getMethod("getType").invoke(actual).equals(deviceClass.getMethod("getType").invoke(expected))
                                        && deviceClass.getMethod("getAddress").invoke(actual).equals(deviceClass.getMethod("getAddress").invoke(expected));
                            }
                            if (!matched) {
                                throw new IllegalStateException("복원된 오디오 장치 불일치");
                            }
                        }
                    }
                    rolledBack = true;
                } catch (Throwable restoreError) {
                    rolledBack = false;
                }
            }
            try {
                result.put("ok", false);
                result.put("step", step);
                result.put("rolledBack", rolledBack);
                result.put("changed", changed);
                result.put("error", cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
                result.put("errorType", cause.getClass().getSimpleName());
            } catch (Exception ignored) {
            }
        }
        System.out.println("ONLYLISTEN_RESULT:" + result);
        System.out.flush();
        System.exit(0);
    }
}
