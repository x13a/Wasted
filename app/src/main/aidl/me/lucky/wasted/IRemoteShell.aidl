// Interface exposed by ShizukuShell running in the Shizuku (ADB-level) process.
// Kept minimal: one method to execute a shell command and return its output.
package me.lucky.wasted;

interface IRemoteShell {
    String executeNow(String command);
}
