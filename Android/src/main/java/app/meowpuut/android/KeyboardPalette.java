package app.meowpuut.android;

enum KeyboardPalette {
    ROSE("玫瑰奶茶", 0xffe3d4d1, 0xff352b31, 0xff91453d, 0xffe69687),
    OCEAN("海鹽藍", 0xffd2dee6, 0xff25333e, 0xff2966a6, 0xff8bc6ef),
    SAGE("鼠尾草綠", 0xffd9dfd2, 0xff2b352e, 0xff477554, 0xff9cc7a1),
    LAVENDER("霧紫", 0xffded8e7, 0xff312b3d, 0xff7554a6, 0xffc2a8f0),
    AMBER("琥珀蜜", 0xffe6dcc8, 0xff3c3125, 0xff96611f, 0xfff0bf6b),
    GRAPHITE("石墨", 0xffd7d8d9, 0xff292b2e, 0xff525e6e, 0xffc2ccd9),
    JADE("墨玉", 0xffc4dcd4, 0xff19332e, 0xff166c59, 0xff83d6bb),
    TERRACOTTA("赤陶", 0xffe8d0bf, 0xff3d2b27, 0xffa44e33, 0xffefa486),
    BERRY("夜莓", 0xffe5d1dc, 0xff3a2638, 0xff86335d, 0xffeea9ce),
    SILVER("月光銀", 0xffd7dce5, 0xff262b38, 0xff4e5d86, 0xffbac8ee);

    private static final KeyboardPalette[] OPTIONS = values();

    final String title;
    private final int lightBoard;
    private final int darkBoard;
    private final int lightAccent;
    private final int darkAccent;

    KeyboardPalette(String title, int lightBoard, int darkBoard, int lightAccent, int darkAccent) {
        this.title = title;
        this.lightBoard = lightBoard;
        this.darkBoard = darkBoard;
        this.lightAccent = lightAccent;
        this.darkAccent = darkAccent;
    }

    static KeyboardPalette at(int index) {
        return index >= 0 && index < OPTIONS.length ? OPTIONS[index] : ROSE;
    }

    int board(boolean dark) { return dark ? darkBoard : lightBoard; }

    int accent(boolean dark) { return dark ? darkAccent : lightAccent; }

    int cap(boolean dark, boolean utility) {
        return MeowpuutKeyboard.tint(board(dark), dark ? 0xffeeeaf0 : 0xfffffdf7,
                dark ? (utility ? .10f : .23f) : (utility ? .22f : .76f));
    }
}
