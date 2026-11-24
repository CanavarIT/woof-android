// Android glue for Woof: stdout/stderr -> logcat (tag "Woof").
// Woof logs through printf/fprintf, which Android would otherwise discard.

#include <android/log.h>
#include <pthread.h>
#include <stdio.h>
#include <unistd.h>

static int pipe_fds[2];

static void *LogThread(void *arg)
{
    char buf[1024];
    ssize_t n;

    (void)arg;

    while ((n = read(pipe_fds[0], buf, sizeof(buf) - 1)) > 0)
    {
        while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r'))
        {
            n--;
        }
        buf[n] = '\0';
        if (n > 0)
        {
            __android_log_write(ANDROID_LOG_INFO, "Woof", buf);
        }
    }
    return NULL;
}

__attribute__((constructor)) static void RedirectStdio(void)
{
    pthread_t thread;

    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    if (pipe(pipe_fds) != 0)
    {
        return;
    }

    dup2(pipe_fds[1], STDOUT_FILENO);
    dup2(pipe_fds[1], STDERR_FILENO);

    if (pthread_create(&thread, NULL, LogThread, NULL) == 0)
    {
        pthread_detach(thread);
    }
}

// ---------------------------------------------------------------------------
// Game state for the touch overlay (TouchControlsView.nativeUiState).
//   bit 0: "touch mode" - menu, title screen, intermission or demo: buttons are hidden
//          and touches act as the mouse;
//   bit 1: autorun is on (Shift then slows down instead of speeding up);
//   bit 2: a Y/N question is on screen and the game waits for an answer.
// ---------------------------------------------------------------------------

#include <jni.h>

#include "doomstat.h"
#include "g_game.h"

int MN_AndroidAwaitingInput(void);

JNIEXPORT jint JNICALL
Java_com_doom_woof_TouchControlsView_nativeUiState(JNIEnv *env, jclass cls)
{
    jint state = 0;

    (void)env;
    (void)cls;

    if (menuactive || gamestate != GS_LEVEL || demoplayback)
    {
        state |= 1;
    }
    if (autorun)
    {
        state |= 2;
    }
    if (MN_AndroidAwaitingInput())
    {
        state |= 4;
    }
    return state;
}
