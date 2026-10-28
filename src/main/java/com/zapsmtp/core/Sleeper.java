package com.zapsmtp.core;

/** Waits between attempts; replaced in tests. */
@FunctionalInterface
interface Sleeper {
  void sleep(long millis) throws InterruptedException;
}
