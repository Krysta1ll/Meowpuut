#import "DocumentContext.h"

NSUUID * _Nullable MPKeyboardDocumentIdentifier(id<UITextDocumentProxy> _Nonnull proxy) {
    return proxy.documentIdentifier;
}
