#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
int mp_start(const char *shared, const char *user);
void mp_stop(void);
uintptr_t mp_session(void);
void mp_destroy(uintptr_t session);
int mp_key(uintptr_t session, int key, int modifiers);
void mp_mode(uintptr_t session, int simplified);
void mp_clear(uintptr_t session);
void mp_commit(uintptr_t session);
void mp_choose(uintptr_t session, int index);
const char *mp_candidates(uintptr_t session, int limit);
const char *mp_snapshot(uintptr_t session);
#ifdef __cplusplus
}
#endif
