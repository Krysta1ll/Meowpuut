#pragma once
// TSF declarations that MinGW-w64's msctf.h (as of llvm-mingw 20261020 and
// Homebrew mingw-w64 14) leaves out. The Windows SDK has them; the guards are
// the SDK's own, so an SDK or a later MinGW header wins.
#include <msctf.h>
#include <ctfutb.h>

#ifndef TF_CLIENTID_NULL
#define TF_CLIENTID_NULL 0
#endif
#ifndef TF_INVALID_GUIDATOM
#define TF_INVALID_GUIDATOM 0
#endif

#ifndef __ITfTextInputProcessorEx_INTERFACE_DEFINED__
#define __ITfTextInputProcessorEx_INTERFACE_DEFINED__
struct ITfTextInputProcessorEx : public ITfTextInputProcessor {
    virtual HRESULT STDMETHODCALLTYPE ActivateEx(ITfThreadMgr *threadMgr, TfClientId clientId, DWORD flags) = 0;
};
#endif

#ifndef __ITfDisplayAttributeProvider_INTERFACE_DEFINED__
#define __ITfDisplayAttributeProvider_INTERFACE_DEFINED__
struct ITfDisplayAttributeProvider : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE EnumDisplayAttributeInfo(IEnumTfDisplayAttributeInfo **result) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetDisplayAttributeInfo(REFGUID guid, ITfDisplayAttributeInfo **result) = 0;
};
#endif

#ifndef __ITfTextLayoutSink_INTERFACE_DEFINED__
#define __ITfTextLayoutSink_INTERFACE_DEFINED__
enum TfLayoutCode { TF_LC_CREATE = 0, TF_LC_CHANGE = 1, TF_LC_DESTROY = 2 };
struct ITfTextLayoutSink : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE OnLayoutChange(ITfContext *context, TfLayoutCode code, ITfContextView *view) = 0;
};
#endif

#ifndef __ITfMenu_INTERFACE_DEFINED__
#define __ITfMenu_INTERFACE_DEFINED__
struct ITfMenu : public IUnknown {
    virtual HRESULT STDMETHODCALLTYPE AddMenuItem(UINT id, DWORD flags, HBITMAP bitmap, HBITMAP mask, const WCHAR *text,
                                                  ULONG length, ITfMenu **submenu) = 0;
};
#endif

#ifndef __ITfLangBarItemButton_INTERFACE_DEFINED__
#define __ITfLangBarItemButton_INTERFACE_DEFINED__
enum TfLBIClick { TF_LBI_CLK_RIGHT = 1, TF_LBI_CLK_LEFT = 2 };
struct ITfLangBarItemButton : public ITfLangBarItem {
    virtual HRESULT STDMETHODCALLTYPE OnClick(TfLBIClick click, POINT point, const RECT *area) = 0;
    virtual HRESULT STDMETHODCALLTYPE InitMenu(ITfMenu *menu) = 0;
    virtual HRESULT STDMETHODCALLTYPE OnMenuSelect(UINT id) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetIcon(HICON *icon) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetText(BSTR *text) = 0;
};
#endif

// UI-less mode (learn.microsoft.com/windows/win32/tsf/uiless-mode-overview). MinGW-w64 has
// ITfUIElement and ITfUIElementMgr but not the predefined elements. Method order, IIDs and flag
// values follow the Windows SDK's msctf.h (as generated into Microsoft's windows-rs 0.58 bindings).
#ifndef __ITfCandidateListUIElement_INTERFACE_DEFINED__
#define __ITfCandidateListUIElement_INTERFACE_DEFINED__
struct ITfCandidateListUIElement : public ITfUIElement {
    virtual HRESULT STDMETHODCALLTYPE GetUpdatedFlags(DWORD *flags) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetDocumentMgr(ITfDocumentMgr **documentMgr) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetCount(UINT *count) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetSelection(UINT *index) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetString(UINT index, BSTR *text) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetPageIndex(UINT *index, UINT size, UINT *pageCount) = 0;
    virtual HRESULT STDMETHODCALLTYPE SetPageIndex(UINT *index, UINT pageCount) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetCurrentPage(UINT *page) = 0;
};
#endif
#ifndef __ITfCandidateListUIElementBehavior_INTERFACE_DEFINED__
#define __ITfCandidateListUIElementBehavior_INTERFACE_DEFINED__
struct ITfCandidateListUIElementBehavior : public ITfCandidateListUIElement {
    virtual HRESULT STDMETHODCALLTYPE SetSelection(UINT index) = 0;
    virtual HRESULT STDMETHODCALLTYPE Finalize() = 0;
    virtual HRESULT STDMETHODCALLTYPE Abort() = 0;
};
#endif
#ifndef TF_CLUIE_DOCUMENTMGR
#define TF_CLUIE_DOCUMENTMGR 0x00000001
#define TF_CLUIE_COUNT 0x00000002
#define TF_CLUIE_SELECTION 0x00000004
#define TF_CLUIE_STRING 0x00000008
#define TF_CLUIE_PAGEINDEX 0x00000010
#define TF_CLUIE_CURRENTPAGE 0x00000020
#endif
#ifndef TF_INVALID_UIELEMENTID
#define TF_INVALID_UIELEMENTID DWORD(-1)
#endif

#ifndef TF_LBI_STYLE_SHOWNINTRAY
#define TF_LBI_STYLE_SHOWNINTRAY 0x00000002
#define TF_LBI_STYLE_BTN_BUTTON 0x00010000
#define TF_LBI_STYLE_BTN_MENU 0x00020000
#endif
#ifndef TF_LBI_ICON
#define TF_LBI_ICON 0x00000001
#define TF_LBI_TEXT 0x00000002
#define TF_LBI_TOOLTIP 0x00000004
#endif
#ifndef TF_LBMENUF_CHECKED
#define TF_LBMENUF_CHECKED 0x1
#define TF_LBMENUF_RADIOCHECKED 0x8
#endif
#ifndef TF_LBMENUF_SEPARATOR
#define TF_LBMENUF_SEPARATOR 0x4
#endif

namespace meowpuut::tsf {
// Interface and category identifiers, spelled out because libuuid lacks some.
const IID kIidTextInputProcessorEx = {0x6e4e2102, 0xf9cd, 0x433d, {0xb4, 0x96, 0x30, 0x3c, 0xe0, 0x3a, 0x65, 0x07}};
const IID kIidDisplayAttributeProvider = {0xfee47777, 0x163c, 0x4769, {0x99, 0x6a, 0x6e, 0x9c, 0x50, 0xad, 0x8f, 0x54}};
// GUID_TFCAT_TIPCAP_IMMERSIVESUPPORT: usable in Store apps and the Start menu.
const GUID kCategoryImmersive = {0x13a016df, 0x560b, 0x46cd, {0x94, 0x7a, 0x4c, 0x3a, 0xf1, 0xe0, 0xe3, 0x5d}};
// GUID_TFCAT_TIPCAP_SYSTRAYSUPPORT: listed in the taskbar's input switcher.
const GUID kCategorySystray = {0x25504fb4, 0x7bab, 0x4bc1, {0x9c, 0x69, 0xcf, 0x81, 0x89, 0x0f, 0x0e, 0xf5}};
const IID kIidTextLayoutSink = {0x2af2d06a, 0xdd5b, 0x4927, {0xa0, 0xb4, 0x54, 0xf1, 0x9c, 0x91, 0xfa, 0xde}};
const IID kIidLangBarItemButton = {0x28c7f1d0, 0xde25, 0x11d2, {0xaf, 0xdd, 0x00, 0x10, 0x5a, 0x27, 0x99, 0xb5}};
// GUID_LBI_INPUTMODE: the item Windows shows as the input mode in the taskbar.
const GUID kInputModeItem = {0x2c77a81e, 0x41cc, 0x4178, {0xa3, 0xa7, 0x5f, 0x8a, 0x98, 0x75, 0x68, 0xe6}};
// GUID_TFCAT_TIPCAP_UIELEMENTENABLED: shows its UI through ITfUIElementMgr, so a thread activated
// with TF_TMAE_UIELEMENTENABLEDONLY (a game drawing its own candidates) still loads this service.
const GUID kCategoryUIElementEnabled = {0x49d2f9cf, 0x1f5e, 0x11d7, {0xa6, 0xd3, 0x00, 0x06, 0x5b, 0x84, 0x43, 0x5c}};
const IID kIidCandidateListUIElement = {0xea1ea138, 0x19df, 0x11d7, {0xa6, 0xd2, 0x00, 0x06, 0x5b, 0x84, 0x43, 0x5c}};
const IID kIidCandidateListUIElementBehavior = {0x85fad185, 0x58ce, 0x497a, {0x94, 0x60, 0x35, 0x53, 0x66, 0xb6, 0x4b, 0x9a}};
// Identifies Meowpuut's candidate list element (ITfUIElement::GetGUID); new in this version.
const GUID kCandidateElement = {0x532bd5c9, 0x1564, 0x46e4, {0xbc, 0x29, 0x4c, 0x21, 0x03, 0x3d, 0x14, 0x20}};
}
