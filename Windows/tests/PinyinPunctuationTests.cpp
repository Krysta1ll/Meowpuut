#include "../PinyinPunctuation.h"
#include <cassert>

int main() {
    assert(pinyinPunctuation(0xbc) == L'，');
    assert(pinyinPunctuation(0xbe) == L'。');
    assert(pinyinPunctuation(0xba) == L'；');
    assert(pinyinPunctuation(0xbf) == L'？');
    assert(pinyinPunctuation(0xdb) == L'「');
    assert(pinyinPunctuation(0xdd) == L'」');
    assert(pinyinPunctuation(0xdc) == L'、');
    assert(!pinyinPunctuation('A'));
    assert(!pinyinPunctuation(0xde)); // apostrophe splits pinyin syllables
    assert(pinyinQueuedLiteral(0x20));
    assert(!pinyinQueuedLiteral(0x0d)); // editor actions must not become literal newlines
    assert(!pinyinQueuedLiteral(0x08)); // do not swallow host deletion
    assert(!pinyinQueuedLiteral(0x25)); // host cursor movement
    assert(!pinyinQueuedLiteral('A'));
    for (unsigned int key = '0'; key <= '9'; ++key) {
        assert(pinyinQueuedLiteral(key));
        assert(pinyinDigitSelects(key, 0, 3) == (key >= '1' && key <= '3'));
        assert(pinyinDigitSelects(key, 7, 9) == (key >= '1' && key <= '2'));
        assert(!pinyinDigitSelects(key, 0, 0));
    }
}
