#!/bin/sh
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ ! -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
  echo 'First download the official Gradle 8.13 wrapper jar as described in README.md.' >&2
  exit 1
fi
if [ -n "$JAVA_HOME" ]; then JAVA_EXE="$JAVA_HOME/bin/java"; else JAVA_EXE="java"; fi
exec "$JAVA_EXE" -Xmx64m -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
