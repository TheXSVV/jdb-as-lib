/*
 * JDB - Java Debugger
 * Copyright 2017 Johnny Cao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gmail.woodyc40.topics.infra;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Platform details.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public enum Platform {

    WINDOWS,
    MAC,
    LINUX,
    OTHER;

    private static Platform platform;

    public static Platform getPlatform() {
        if (platform == null) {
            String osName = System.getProperty("os.name").toLowerCase();

            if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix"))
                platform = LINUX;
            else if (osName.contains("win"))
                platform = WINDOWS;
            else if (osName.contains("mac"))
                platform = MAC;
            else
                platform = OTHER;
        }

        return platform;
    }

    public static boolean isWindows() {
        return getPlatform() == WINDOWS;
    }

    public static boolean isMac() {
        return getPlatform() == MAC;
    }

    public static boolean isLinux() {
        return getPlatform() == LINUX;
    }

    public static boolean isOther() {
        return getPlatform() == OTHER;
    }
}
