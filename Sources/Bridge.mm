#import <Foundation/Foundation.h>
#include <rime_api.h>
#include "Bridge.h"
#include <string>
static RimeApi *api;
static bool running = false;
int mp_start(const char *shared, const char *user) {
    api = rime_get_api();
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = shared; traits.user_data_dir = user;
    traits.distribution_name = "Meowpuut";
    traits.distribution_code_name = "meowpuut";
    traits.distribution_version = "0.1.0";
    traits.app_name = "rime.meowpuut";
    api->setup(&traits); api->initialize(&traits);
    if (api->start_maintenance(True)) api->join_maintenance_thread();
    running = true;
    return 1;
}
uintptr_t mp_session(void) {
    auto session = api->create_session();
    if (!api->select_schema(session, "bopomofo")) { api->destroy_session(session); return 0; }
    return session;
}
void mp_stop(void) { if (running) { running = false; api->cleanup_all_sessions(); api->finalize(); } }
void mp_destroy(uintptr_t session) { if (running) api->destroy_session(session); }
int mp_key(uintptr_t session, int key, int modifiers) { return api->process_key(session, key, modifiers); }
void mp_mode(uintptr_t session, int simplified) { api->set_option(session, "zh_hans", simplified); }
void mp_clear(uintptr_t session) { api->clear_composition(session); }
void mp_commit(uintptr_t session) { api->commit_composition(session); }
void mp_choose(uintptr_t session, int index) { api->select_candidate(session, index); }
const char *mp_candidates(uintptr_t session, int limit) {
    static std::string result;
    NSMutableArray *items = [NSMutableArray array];
    RimeCandidateListIterator iterator = {};
    if (api->candidate_list_begin(session, &iterator)) {
        while ((int)items.count < limit && api->candidate_list_next(&iterator)) {
            [items addObject:@{@"index":@(iterator.index), @"text":@(iterator.candidate.text)}];
        }
        api->candidate_list_end(&iterator);
    }
    NSData *data = [NSJSONSerialization dataWithJSONObject:items options:0 error:nil];
    result.assign((const char *)data.bytes, data.length);
    return result.c_str();
}
const char *mp_snapshot(uintptr_t session) {
    static std::string result;
    NSMutableArray *candidates = [NSMutableArray array];
    NSString *preedit = @"", *commit = @"";
    int highlighted = 0;
    int page = 0; int pageSize = 7; bool last = true;
    RIME_STRUCT(RimeContext, ctx);
    if (api->get_context(session, &ctx)) {
        if (ctx.composition.preedit) preedit = @(ctx.composition.preedit);
        highlighted = ctx.menu.highlighted_candidate_index;
        page = ctx.menu.page_no; last = ctx.menu.is_last_page;
        pageSize = ctx.menu.page_size;
        for (int i = 0; i < ctx.menu.num_candidates; i++) {
            [candidates addObject:@(ctx.menu.candidates[i].text)];
        }
        api->free_context(&ctx);
    }
    RIME_STRUCT(RimeCommit, committed);
    if (api->get_commit(session, &committed)) {
        if (committed.text) commit = @(committed.text);
        api->free_commit(&committed);
    }
    const char *input = api->get_input(session);
    NSData *data = [NSJSONSerialization dataWithJSONObject:@{@"preedit":preedit, @"commit":commit, @"candidates":candidates, @"highlighted":@(highlighted), @"page":@(page), @"pageSize":@(pageSize), @"lastPage":@(last), @"input":input ? @(input) : @""} options:0 error:nil];
    result.assign((const char *)data.bytes, data.length);
    return result.c_str();
}
