#import <UIKit/UIKit.h>

// UIKit can return nil while its document proxy is being attached, despite the
// nonnull annotation on documentIdentifier in the SDK.
FOUNDATION_EXPORT NSUUID * _Nullable MPKeyboardDocumentIdentifier(id<UITextDocumentProxy> _Nonnull proxy);
