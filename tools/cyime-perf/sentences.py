"""The same unsplit pinyin stream is sent to both IMEs."""

SENTENCES = {
    "lyric": ("cang mang de tian ya shi wo de ai mian yan de qing shan jiao xia hua zheng kai",
              "苍茫的天涯是我的爱绵延的青山脚下花正开"),
    "park": ("wo men jin tian xia wu yi qi qu gong yuan san bu ran hou hui jia chi fan",
             "我们今天下午一起去公园散步然后回家吃饭"),
    "work": ("wo jin tian zao shang qi chuang yi hou chi le zao can jiu qu gong si shang ban",
             "我今天早上起床以后吃了早餐就去公司上班"),
    "weather": ("ru guo ming tian tian qi hao de hua wo men jiu yi qi chu qu zou yi zou",
                "如果明天天气好的话我们就一起出去走一走"),
}

LETTERS = "".join(sorted({letter for code, _ in SENTENCES.values() for letter in code if letter != " "}))
