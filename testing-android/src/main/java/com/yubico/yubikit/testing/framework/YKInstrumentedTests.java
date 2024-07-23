/*
 * Copyright (C) 2022-2024 Yubico.
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

package com.yubico.yubikit.testing.framework;

import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.yubico.yubikit.core.Transport;
import com.yubico.yubikit.core.YubiKeyDevice;
import com.yubico.yubikit.testing.ScpParameters;
import com.yubico.yubikit.testing.StaticTestState;
import com.yubico.yubikit.testing.TestActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

import javax.annotation.Nullable;

public class YKInstrumentedTests {

    private TestActivity activity;
    protected YubiKeyDevice device = null;

    @Rule
    public final TestName name = new TestName();

    @Rule
    public final ActivityScenarioRule<TestActivity> scenarioRule = new ActivityScenarioRule<>(TestActivity.class);

    @Before
    public void getYubiKey() throws InterruptedException {
        scenarioRule.getScenario().onActivity((TestActivity activity) -> this.activity = activity);
        device = activity.awaitSession(getClass().getSimpleName(), name.getMethodName());
        StaticTestState.scpParameters = new ScpParameters(device, getScpKid());
        StaticTestState.currentDevice = device;
    }

    @After
    public void after() throws InterruptedException {
        releaseYubiKey();
    }

    public void releaseYubiKey() throws InterruptedException {
        activity.returnSession(device);
        device = null;
        activity = null;
        StaticTestState.currentDevice = null;
        StaticTestState.scpParameters = null;
    }

    public interface Callback {
        void invoke(YubiKeyDevice value) throws Throwable;
    }

    protected void withDevice(Callback callback) throws Throwable {
        callback.invoke(device);
    }

    protected YubiKeyDevice reconnectDevice() {
        try {
            if (device.getTransport() == Transport.NFC) {
                releaseYubiKey();
                getYubiKey();
            }
            return device;
        } catch (InterruptedException e) {
            throw new RuntimeException("Failure during reconnect", e);
        }
    }

    @Nullable
    protected Byte getScpKid() {
        return null;
    }
}

