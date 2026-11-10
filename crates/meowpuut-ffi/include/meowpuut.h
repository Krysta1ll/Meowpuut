// C ABI of the Rust engine (crates/meowpuut-ffi). Drop-in replacement for the
// former C++ Core/MeowpuutCore.h; existing declarations are unchanged.
#ifndef MEOWPUUT_CORE_H
#define MEOWPUUT_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MPNLexicon MPNLexicon;
typedef struct MPNSession MPNSession;

// The loader accepts either a MIMO-BIN-1 binary image or a UTF-8 TSV dictionary.
// Sessions retain the loaded lexicon independently.
MPNLexicon *mpn_lexicon_create(const char *data, size_t size);
// Extracts and loads either Pinyin (pinyin != 0) or Zhuyin (pinyin == 0) from a
// mapped MIMO-BASE-1 (mimo_base.bin) container.
MPNLexicon *mpn_lexicon_create_from_base(const char *data, size_t size, int pinyin);
void mpn_lexicon_destroy(MPNLexicon *lexicon);
// Heap bytes held by the lexicon tables (memory diagnostics); 0 for NULL.
size_t mpn_lexicon_heap_bytes(const MPNLexicon *lexicon);
MPNSession *mpn_session_create(const MPNLexicon *lexicon);
void mpn_session_destroy(MPNSession *session);

// Printable ASCII keys and the same X11 editing keys as the Rime adapter.
// A session is used on one thread. Copy returned strings before the next mutation.
// At the limit, the next valid key preserves the old preedit as a commit and
// begins a new composition; boundary tone marks complete the old reading.
size_t mpn_max_input_bytes(void);
int mpn_key(MPNSession *session, int32_t key);
void mpn_set_simplified(MPNSession *session, int simplified);
void mpn_clear(MPNSession *session);
// Restore a Chinese prefix only while rebuilding the same partial composition.
// NULL/empty clears it. The getter points into the session until its mutation.
void mpn_set_segment_context(MPNSession *session, const char *text);
const char *mpn_segment_context(const MPNSession *session);
void mpn_commit(MPNSession *session);
int mpn_choose(MPNSession *session, size_t index);
const char *mpn_input(const MPNSession *session);
const char *mpn_preedit(const MPNSession *session);
const char *mpn_committed(const MPNSession *session);
void mpn_clear_commit(MPNSession *session);
size_t mpn_candidate_count(const MPNSession *session);
const char *mpn_candidate(const MPNSession *session, size_t index);
// Raw input bytes consumed when selecting this candidate; 0 for invalid input.
size_t mpn_candidate_consumed(const MPNSession *session, size_t index);
// Provenance describes how a candidate was generated, not its confidence.
// A fully spelled path is an exact reading, but may not be a plausible phrase.
typedef enum {
    MPN_CANDIDATE_UNKNOWN = 0,
    MPN_CANDIDATE_EXACT = 1,
    MPN_CANDIDATE_PATH = 2,
    MPN_CANDIDATE_PARTIAL = 3,
    MPN_CANDIDATE_CORRECTION = 4
} MPNCandidateKind;
typedef enum {
    MPN_SCORE_NONE = 0,
    MPN_SCORE_LEXICON = 1,
    MPN_SCORE_PATH = 2,
    MPN_SCORE_FALLBACK = 3
} MPNCandidateScoreKind;
typedef struct {
    uint32_t kind;
    uint32_t score_kind;
    uint64_t consumed_bytes;
    // First raw ASCII input byte retained after selection. This may be a
    // valid next word as well as an unfinished or unrecognised suffix.
    uint64_t remaining_offset_bytes;
    // Generation score before session-context reordering. Interpret only
    // within score_kind; it is not a probability or a calibrated confidence.
    double score;
} MPNCandidateInfo;
// Writes the fields fitting in info_size; returns 0 for invalid arguments.
// The info and string describe the same candidate until the next mutation.
int mpn_candidate_info(const MPNSession *session, size_t index, MPNCandidateInfo *info, size_t info_size);
size_t mpn_page(const MPNSession *session);

// Optional Japanese words for mixed pinyin/romaji. Registration and decoding
// are local to the session; a returned string remains valid until the next call.
// Weight 3500 denotes an ambiguous all-kanji pinyin alias: it participates in
// a mixed sentence only when another, higher-confidence Japanese word is present.
// Two-letter readings are rejected except the high-confidence reading "au";
// mixed decoding of that short reading also requires independent evidence.
int mpn_add_japanese_word(MPNSession *session, const char *reading, const char *text, int weight);
const char *mpn_mixed_pinyin(MPNSession *session, const char *reading, int simplified);
// A complete leading segment and its ASCII reading length, leaving the suffix untouched.
const char *mpn_mixed_pinyin_prefix(MPNSession *session, const char *reading, int simplified, size_t *consumed);

// Next-word associations for already committed text (trailing Han characters
// are used; anything else yields 0). Output follows mpn_set_simplified. Returns
// the count (at most min(limit, 64)); strings stay valid until the next
// mpn_predict on this session.
size_t mpn_predict(MPNSession *session, const char *context, size_t limit);
const char *mpn_prediction(const MPNSession *session, size_t index);

#ifdef __cplusplus
}
#endif

#endif
