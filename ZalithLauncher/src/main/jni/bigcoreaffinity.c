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

// ONA: in-order LITTLE cores by their ARM part number (A7, A35, A53, A55, A510, A520). Clocks
// alone can't tell them apart: Snapdragon 680 (Redmi Pad SE) has A53s at 1.9 GHz next to A73s at
// 2.4 GHz, 79% of the top clock, the same ratio as the performance cores of an 8 Elite Gen 5. On
// Exynos 9810 / 9820 / 9825 (Galaxy S9, Note 9, S10, Note 10) the A55s run the game threads slowly too.
static int bigcore_is_little_part(unsigned int implementer, unsigned int part) {
    if (implementer != 0x41) return 0; // ARM Ltd. designs only, custom cores (Mongoose, Kryo Gold) stay
    return part == 0xc07 || part == 0xd04 || part == 0xd03 || part == 0xd05 || part == 0xd46 || part == 0xd80;
}

// Fills little[i] = 1 for every LITTLE core listed in /proc/cpuinfo. @return number of cores read
static int bigcore_read_parts(long corecnt, unsigned char* little) {
    FILE* cpuinfo = fopen("/proc/cpuinfo", "r");
    if(cpuinfo == NULL) return 0;
    char line[256];
    long current = -1;
    unsigned int implementer = 0;
    int read_cores = 0;
    while(fgets(line, sizeof(line), cpuinfo) != NULL) {
        unsigned int value;
        long processor;
        if(sscanf(line, "processor : %ld", &processor) == 1 || sscanf(line, "processor\t: %ld", &processor) == 1) {
            current = processor;
            implementer = 0;
        } else if(strncmp(line, "CPU implementer", 15) == 0 && sscanf(strchr(line, ':') + 1, " %x", &value) == 1) {
            implementer = value;
        } else if(strncmp(line, "CPU part", 8) == 0 && sscanf(strchr(line, ':') + 1, " %x", &value) == 1) {
            if(current >= 0 && current < corecnt) {
                little[current] = (unsigned char) bigcore_is_little_part(implementer, value);
                read_cores++;
            }
        }
    }
    fclose(cpuinfo);
    return read_cores;
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

    // ONA: only real LITTLE cores are left out, those well below the fastest core. Chips without
    // LITTLE cores (Snapdragon 8 Elite / 8 Elite Gen 5: 2 prime + 6 performance cores) used to lose
    // their 6 performance cores here because they are the slowest cluster, which starved the
    // render, server and chunk threads of a heavy modpack.
    unsigned long little_limit = max_freq / 10 * 7;
    static unsigned char little_part[CPU_SETSIZE];
    memset(little_part, 0, sizeof(little_part));
    int parts_known = bigcore_read_parts(corecnt, little_part) > 0;
    int any_big_part = 0;
    for(long i = 0; i < corecnt && parts_known; i++) {
        if(core_freqs[i] != 0 && !little_part[i]) any_big_part = 1;
    }
    cpu_set_t bigcore_affinity_set;
    int big_core_count = 0;
    int online_count = 0;
    // ONA: the game needs at least 4 cores (render, world, chunk builders, GC). Chips with only 1-2
    // big cores (MediaTek Helio G85 / G88 / G96 / G99: 2x A75/A76 + 6x A55) must keep their LITTLE
    // cores, so the LITTLE-part rule is dropped there and only the clock rule is used.
    for(int pass = 0; pass < 2; pass++) {
        int use_parts = pass == 0 && parts_known && any_big_part;
        CPU_ZERO(&bigcore_affinity_set);
        big_core_count = 0;
        online_count = 0;
        for(long i = 0; i < corecnt; i++) {
            if(core_freqs[i] == 0) continue;
            online_count++;
            // a known LITTLE design is left out whatever its clock, when bigger cores exist
            if(use_parts && little_part[i]) continue;
            if(core_freqs[i] >= little_limit) {
                CPU_SET(i, &bigcore_affinity_set);
                big_core_count++;
            }
        }
        if(big_core_count >= 4 || !use_parts) break;
        printf("bigcore: only %d big cores, keeping the LITTLE cores too\n", big_core_count);
    }
    if(big_core_count < 4 && big_core_count < online_count) {
        printf("bigcore: %d fast cores are too few for the game, keeping default affinity\n", big_core_count);
        return;
    }
    if(big_core_count == 0 || big_core_count == online_count) {
        printf("bigcore: no LITTLE cores (%d fast cores), keeping default affinity\n", online_count);
        return;
    }

    if(sched_setaffinity(0, sizeof(cpu_set_t), &bigcore_affinity_set) != 0) {
        printf("bigcore: setting affinity failed: %s\n", strerror(errno));
    }else{
        printf("bigcore: moved game threads onto %d big cores (max %lu kHz)\n", big_core_count, max_freq);
    }
}
