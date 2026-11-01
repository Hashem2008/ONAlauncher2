//
// Created by maks on 19.06.2023.
//

#define _GNU_SOURCE // we are GNU GPLv3

#include <linux/limits.h>
#include <unistd.h>
#include <stdio.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sched.h>
#include <string.h>
#include <errno.h>

#define FREQ_MAX 256
void bigcore_format_cpu_path(char* buffer, unsigned int cpu_core) {
    snprintf(buffer, PATH_MAX, "/sys/devices/system/cpu/cpu%u/cpufreq/cpuinfo_max_freq", cpu_core);
}

static unsigned long bigcore_read_max_freq(unsigned int cpu_core) {
    char path_buffer[PATH_MAX];
    char freq_buffer[FREQ_MAX];
    bigcore_format_cpu_path(path_buffer, cpu_core);
    int corefreqfd = open(path_buffer, O_RDONLY);
    if(corefreqfd == -1) return 0;
    ssize_t read_count = read(corefreqfd, freq_buffer, FREQ_MAX - 1);
    close(corefreqfd);
    if(read_count <= 0) return 0;
    freq_buffer[read_count] = 0;
    return strtoul(freq_buffer, NULL, 10);
}

// Restricts the current thread (and the threads it creates later, since affinity is inherited)
// to every core that is faster than the slowest cluster.
// Pinning to a single core would also squeeze the integrated server and chunk builder threads onto it.
void bigcore_set_affinity() {
    long corecnt = sysconf(_SC_NPROCESSORS_CONF);
    if(corecnt <= 0 || corecnt > CPU_SETSIZE) {
        printf("bigcore: unusable core count %ld\n", corecnt);
        return;
    }

    static unsigned long core_freqs[CPU_SETSIZE]; // static: 8 KB is too much for a native stack
    unsigned long min_freq = 0;
    unsigned long max_freq = 0;
    for(long i = 0; i < corecnt; i++) {
        core_freqs[i] = bigcore_read_max_freq(i);
        if(core_freqs[i] == 0) continue; // offline or unreadable core
        if(min_freq == 0 || core_freqs[i] < min_freq) min_freq = core_freqs[i];
        if(core_freqs[i] > max_freq) max_freq = core_freqs[i];
    }

    if(max_freq == 0 || min_freq == max_freq) {
        printf("bigcore: no big.LITTLE layout detected, keeping default affinity\n");
        return;
    }

    cpu_set_t bigcore_affinity_set;
    CPU_ZERO(&bigcore_affinity_set);
    int big_core_count = 0;
    for(long i = 0; i < corecnt; i++) {
        if(core_freqs[i] > min_freq) {
            CPU_SET(i, &bigcore_affinity_set);
            big_core_count++;
        }
    }

    if(sched_setaffinity(0, sizeof(cpu_set_t), &bigcore_affinity_set) != 0) {
        printf("bigcore: setting affinity failed: %s\n", strerror(errno));
    }else{
        printf("bigcore: moved game threads onto %d big cores (max %lu kHz)\n", big_core_count, max_freq);
    }
}
