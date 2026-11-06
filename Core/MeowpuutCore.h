#ifndef MEOWPUUT_CORE_H
#define MEOWPUUT_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MPNLexicon MPNLexicon;
typedef struct MPNSession MPNSession;

// The loader copies the UTF-8 dictionary. Sessions retain it independently.
MPNLexicon *mpn_lexicon_create(const char *data, size_t size);
void mpn_lexicon_destroy(MPNLexicon *lexicon);
MPNSession *mpn_session_create(const MPNLexicon *lexicon);
void mpn_session_destroy(MPNSession *session);

// Printable ASCII keys and the same X11 editing keys as the Rime adapter.
// A session is used on one thread. Copy returned strings before the next mutation.
int mpn_key(MPNSession *session, int32_t key);
void mpn_set_simplified(MPNSession *session, int simplified);
void mpn_clear(MPNSession *session);
void mpn_commit(MPNSession *session);
int mpn_choose(MPNSession *session, size_t index);
const char *mpn_input(const MPNSession *session);
const char *mpn_preedit(const MPNSession *session);
const char *mpn_committed(const MPNSession *session);
void mpn_clear_commit(MPNSession *session);
size_t mpn_candidate_count(const MPNSession *session);
const char *mpn_candidate(const MPNSession *session, size_t index);
size_t mpn_page(const MPNSession *session);

#ifdef __cplusplus
}
#endif

#endif
