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

package com.yubico.yubikit.testing.piv;

import com.yubico.yubikit.core.smartcard.scp.ScpKid;
import com.yubico.yubikit.testing.PinComplexityDeviceTests;
import com.yubico.yubikit.testing.SmokeTest;
import com.yubico.yubikit.testing.framework.PivInstrumentedTests;

import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;

import javax.annotation.Nullable;

@RunWith(Suite.class)
@Suite.SuiteClasses({
        PivTests.NoScpTests.class,
        PivTests.Scp11bTests.class,
        PivJcaProviderTests.NoScpTests.class,
        PivJcaProviderTests.Scp11bTests.class
})
public class PivTests {
    public static class NoScpTests extends PivInstrumentedTests {
        @Test
        @Category(SmokeTest.class)
        public void testPin() throws Throwable {
            withPivSession(PivDeviceTests::testPin);
        }

        @Test
        public void testPuk() throws Throwable {
            withPivSession(PivDeviceTests::testPuk);
        }

        @Test
        public void testManagementKey() throws Throwable {
            withPivSession(PivDeviceTests::testManagementKey);
        }

        @Test
        public void testManagementKeyType() throws Throwable {
            withPivSession(PivDeviceTests::testManagementKeyType);
        }

        @Test
        public void testPutUncompressedCertificate() throws Throwable {
            withPivSession(PivCertificateTests::putUncompressedCertificate);
        }

        @Test
        @Category(SmokeTest.class)
        public void testPutCompressedCertificate() throws Throwable {
            withPivSession(PivCertificateTests::putCompressedCertificate);
        }

        @Test
        public void testPinComplexity() throws Throwable {
            withPivSession(PinComplexityDeviceTests::testPivPinComplexity);
        }
    }

    public static class Scp11bTests extends NoScpTests {
        @Override
        @Nullable
        protected Byte getScpKid() {
            return ScpKid.SCP11b;
        }
    }
}
