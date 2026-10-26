package com.onlylisten.app;

import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class AudioControlDaemon {
    public static void main(String[] args) {
        try {
            if (Process.myUid() != 2000 && Process.myUid() != 0) {
                throw new SecurityException("사용자가 허용한 디버깅 권한으로 시작해야 합니다.");
            }
            String apk = System.getProperty("java.class.path");
            if (apk == null || !new File(apk).isFile()) {
                throw new IllegalStateException("앱 실행 파일을 찾지 못했습니다.");
            }
            String stat = new String(java.nio.file.Files.readAllBytes(new File("/proc/self/stat").toPath()),
                    StandardCharsets.US_ASCII);
            String[] processFields = stat.substring(stat.lastIndexOf(')') + 2).split("\\s+");
            if (Integer.parseInt(processFields[3]) != Process.myPid()) {
                throw new IllegalStateException("디버깅 연결과 제어 프로세스를 분리하지 못했습니다.");
            }
            ByteArrayOutputStream keyLine = new ByteArrayOutputStream();
            int next;
            while ((next = System.in.read()) != '\n') {
                if (next < 0 || keyLine.size() >= 128) {
                    throw new IllegalArgumentException("내부 제어 인증 정보를 받지 못했습니다.");
                }
                keyLine.write(next);
            }
            byte[] secret = Base64.decode(keyLine.toString(StandardCharsets.US_ASCII.name()).trim(), Base64.NO_WRAP);
            if (secret.length != 32) {
                throw new IllegalArgumentException("내부 제어 인증 정보가 올바르지 않습니다.");
            }
            SecretKeySpec key = new SecretKeySpec(secret, "HmacSHA256");
            SecureRandom random = new SecureRandom();
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
            server.setSoTimeout(2000);
            ExecutorService routing = Executors.newSingleThreadExecutor();
            ThreadPoolExecutor clients = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(4));
            ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
            JSONObject started = new JSONObject().put("ok", true).put("protocol", 1)
                    .put("port", server.getLocalPort()).put("pid", Process.myPid())
                    .put("uid", Process.myUid()).put("apkPath", apk);
            System.out.println("ONLYLISTEN_CONTROL:" + started);
            System.out.flush();
            FileDescriptor nullDescriptor = Os.open("/dev/null", OsConstants.O_RDWR, 0);
            Os.dup2(nullDescriptor, 0);
            Os.dup2(nullDescriptor, 1);
            Os.dup2(nullDescriptor, 2);
            Os.close(nullDescriptor);

            try {
                while (new File(apk).isFile()) {
                    Socket accepted;
                    try {
                        accepted = server.accept();
                    } catch (SocketTimeoutException idle) {
                        continue;
                    }
                    Socket client = accepted;
                    try {
                        clients.execute(() -> {
                            ScheduledFuture<?> deadline = deadlines.schedule(() -> {
                                try {
                                    client.close();
                                } catch (Exception ignored) {
                                }
                            }, 28, TimeUnit.SECONDS);
                            try (client; DataInputStream input = new DataInputStream(client.getInputStream());
                                 DataOutputStream output = new DataOutputStream(client.getOutputStream())) {
                                client.setSoTimeout(4000);
                                byte[] clientNonce = new byte[32];
                                input.readFully(clientNonce);
                                byte[] serverNonce = new byte[32];
                                random.nextBytes(serverNonce);
                                Mac authentication = Mac.getInstance("HmacSHA256");
                                authentication.init(key);
                                authentication.update("OnlyListen server v1".getBytes(StandardCharsets.US_ASCII));
                                authentication.update(clientNonce);
                                authentication.update(serverNonce);
                                output.write(serverNonce);
                                output.write(authentication.doFinal());
                                output.flush();
                                int size = input.readInt();
                                if (size < 2 || size > 65536) {
                                    return;
                                }
                                byte[] requestBytes = new byte[size];
                                input.readFully(requestBytes);
                                byte[] signature = new byte[32];
                                input.readFully(signature);
                                authentication.reset();
                                authentication.update("OnlyListen request v1".getBytes(StandardCharsets.US_ASCII));
                                authentication.update(clientNonce);
                                authentication.update(serverNonce);
                                authentication.update(requestBytes);
                                if (!MessageDigest.isEqual(authentication.doFinal(), signature)) {
                                    return;
                                }
                                JSONObject request = new JSONObject(new String(requestBytes, StandardCharsets.UTF_8));
                                String action = request.optString("action");
                                JSONObject response;
                                if (!apk.equals(request.optString("apkPath"))) {
                                    response = new JSONObject().put("ok", false).put("changed", false)
                                            .put("step", "내부 제어 연결")
                                            .put("error", "앱이 업데이트되어 제어 프로세스를 다시 시작해야 합니다.");
                                } else if (action.equals("ping")) {
                                    response = new JSONObject().put("ok", true).put("protocol", 1)
                                            .put("apkPath", apk);
                                } else if (action.equals("snapshot") || action.equals("status")
                                        || action.equals("on") || action.equals("off")) {
                                    String backup = request.optString("backup");
                                    if ((action.equals("on") || action.equals("off"))
                                            && (backup.isEmpty() || backup.length() > 32768)) {
                                        throw new IllegalArgumentException("원래 설정의 복원 정보가 올바르지 않습니다.");
                                    }
                                    Future<JSONObject> result = routing.submit(() -> {
                                        java.lang.Process command = null;
                                        ScheduledFuture<?> commandDeadline = null;
                                        try {
                                            List<String> arguments = new ArrayList<>();
                                            arguments.add("/system/bin/app_process");
                                            arguments.add("/system/bin");
                                            arguments.add("com.onlylisten.app.AudioRouteCommand");
                                            arguments.add(action);
                                            if (action.equals("on") || action.equals("off")) {
                                                arguments.add(backup);
                                            }
                                            ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
                                            builder.environment().put("CLASSPATH", apk);
                                            command = builder.start();
                                            java.lang.Process running = command;
                                            commandDeadline = deadlines.schedule(running::destroyForcibly, 20, TimeUnit.SECONDS);
                                            String text;
                                            try (InputStream commandOutput = command.getInputStream();
                                                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                                                byte[] buffer = new byte[4096];
                                                int count;
                                                while ((count = commandOutput.read(buffer)) != -1) {
                                                    bytes.write(buffer, 0, count);
                                                    if (bytes.size() > 131072) {
                                                        throw new IllegalStateException("오디오 시스템 응답이 너무 큽니다.");
                                                    }
                                                }
                                                text = bytes.toString(StandardCharsets.UTF_8.name());
                                            }
                                            if (!command.waitFor(1, TimeUnit.SECONDS)) {
                                                throw new IllegalStateException("오디오 제어 실행을 완료하지 못했습니다.");
                                            }
                                            int marker = text.lastIndexOf("ONLYLISTEN_RESULT:");
                                            if (marker < 0) {
                                                throw new IllegalStateException("오디오 제어 코드가 응답하지 않았습니다.");
                                            }
                                            return new JSONObject(text.substring(marker + "ONLYLISTEN_RESULT:".length())
                                                    .split("\\r?\\n", 2)[0]);
                                        } catch (Exception | LinkageError error) {
                                            return new JSONObject().put("ok", false)
                                                    .put("step", "휴대폰 내부 오디오 제어")
                                                    .put("error", error.getMessage() == null
                                                            ? error.getClass().getSimpleName() : error.getMessage())
                                                    .put("changed", action.equals("on") || action.equals("off"))
                                                    .put("rolledBack", false);
                                        } finally {
                                            if (commandDeadline != null) {
                                                commandDeadline.cancel(false);
                                            }
                                            if (command != null && command.isAlive()) {
                                                command.destroyForcibly();
                                            }
                                        }
                                    });
                                    try {
                                        response = result.get(24, TimeUnit.SECONDS);
                                    } catch (Exception error) {
                                        result.cancel(true);
                                        response = new JSONObject().put("ok", false).put("step", "휴대폰 내부 오디오 제어")
                                                .put("error", "내부 제어 응답 시간이 지났습니다.")
                                                .put("changed", action.equals("on") || action.equals("off"))
                                                .put("rolledBack", false);
                                    }
                                } else {
                                    response = new JSONObject().put("ok", false).put("changed", false)
                                            .put("step", "요청 확인").put("error", "지원하지 않는 제어 요청입니다.");
                                }
                                response.put("controlPid", Process.myPid()).put("controlUid", Process.myUid())
                                        .put("transport", "device-local");
                                byte[] responseBytes = response.toString().getBytes(StandardCharsets.UTF_8);
                                if (responseBytes.length > 131072) {
                                    return;
                                }
                                authentication.reset();
                                authentication.update("OnlyListen response v1".getBytes(StandardCharsets.US_ASCII));
                                authentication.update(clientNonce);
                                authentication.update(serverNonce);
                                authentication.update(responseBytes);
                                output.writeInt(responseBytes.length);
                                output.write(responseBytes);
                                output.write(authentication.doFinal());
                                output.flush();
                            } catch (Exception | LinkageError ignored) {
                            } finally {
                                deadline.cancel(false);
                            }
                        });
                    } catch (RuntimeException rejected) {
                        client.close();
                    }
                }
            } finally {
                server.close();
                clients.shutdownNow();
                routing.shutdownNow();
                deadlines.shutdownNow();
            }
        } catch (Throwable error) {
            try {
                System.out.println("ONLYLISTEN_CONTROL:" + new JSONObject().put("ok", false)
                        .put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
                System.out.flush();
            } catch (Exception ignored) {
            }
        }
        System.exit(0);
    }
}
