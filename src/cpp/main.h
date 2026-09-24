#ifndef BYPASS_NATIVE_H
#define BYPASS_NATIVE_H

void clear_params(void);
int parse_args(int argc, char **argv);
int event_loop(int fd);

#endif
