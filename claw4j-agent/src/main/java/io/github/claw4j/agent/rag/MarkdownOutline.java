package io.github.claw4j.agent.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 markdown 原文里抽标题与章节路径。只服务于一件事：给块加一行"这段属于哪份文档的哪一章"。
 *
 * <p>为什么需要它：语义切块生效后平均 3.9 段/份，一段 350 字的块可能只写"押金在 7 个工作日内
 * 原路退回"，里面没有"备机"、没有"租赁"。同一种说法散在多份文档里时，几块的向量几乎一样，
 * 谁进候选只能靠运气。把标题与章节路径前置进块文本，向量里就有了这份文档的主题词。
 *
 * <p>只认 ATX 风格（{@code # } … {@code ###### }），且跳过围栏代码块里的行——文档里贴一段
 * shell 注释（{@code # 装依赖}）不该被当成一级标题，那会把整篇的章节路径改成一句命令。
 *
 * <p>不做完整 markdown 解析：setext 标题（下划线式）、HTML 标题、列表项里的 {@code #} 都不认。
 * 语料是仓库里的手写文档，用的是 ATX；真出现别的写法，退化成"没有章节路径"，不影响正文。
 */
final class MarkdownOutline {

    /**
     * 一行标题及其在原文里的起始下标。
     *
     * @param offset 该行第一个字符在原文中的下标
     * @param level  级别，1–6
     * @param text   标题文本（已去掉 {@code #} 与前后的空白）
     */
    record Heading(int offset, int level, String text) {
    }

    /**
     * 一个可作为"章节"的位置：某条标题的起点，加上它到根的完整路径。
     *
     * @param offset 标题行在原文中的下标
     * @param path   章节链，例如 {@code 第二章 退货 › 2.3 运费承担}
     */
    record Section(int offset, String path, String title) {
    }

    /** 前缀最多这么多字符。章节标题可能是整句，无限长会把块的有效内容挤掉。 */
    static final int MAX_PREFIX_CHARS = 120;

    /** 章节链最多保留几级（从最深的往上数）。四级以上通常已经是条目，不是章节。 */
    private static final int MAX_PATH_DEPTH = 3;

    private static final String SEPARATOR = " › ";

    private MarkdownOutline() {
    }

    /**
     * 文档标题：第一个一级标题；没有就用文件名。
     *
     * <p>回退到文件名而不是返回空串：知识库里的文件都是有意义的名字（{@code after-sales.md}），
     * 而空标题会让前缀变成 {@code 【 › 第二章】}，读起来像 bug。
     *
     * @param content 原文
     * @param path    相对文档目录的路径
     * @return 非空的标题文本
     */
    static String title(String content, String path) {
        for (Heading heading : headings(content)) {
            if (heading.level() == 1) {
                return heading.text();
            }
        }
        return fileName(path);
    }

    /**
     * 所有能当章节起点的位置，按出现顺序。
     *
     * @param content 原文
     * @return 每个标题一条，路径已按层级压平
     */
    static List<Section> sections(String content) {
        List<Section> sections = new ArrayList<>();
        List<Heading> stack = new ArrayList<>();
        for (Heading heading : headings(content)) {
            // 弹到第一个级别比它浅的标题：`##` 之后遇到 `##` 要替换，遇到 `#` 要整条换掉。
            while (!stack.isEmpty() && stack.getLast().level() >= heading.level()) {
                stack.removeLast();
            }
            stack.add(heading);
            // 一级标题只是文档标题，不是章节：它已经拼在前缀最前面了。留进 sections 的话，
            // 这条路径是空串，等于凭空多一个"没有章节"的位置。
            if (heading.level() > 1) {
                sections.add(new Section(heading.offset(), pathOf(stack), heading.text()));
            }
        }
        return sections;
    }

    /**
     * 这个位置属于哪一章：最后一个起点不晚于它的章节。
     *
     * @param sections {@link #sections} 的结果
     * @param offset   块在原文中的起始下标
     * @return 章节链；在第一条标题之前时是空串
     */
    static String at(List<Section> sections, int offset) {
        String path = "";
        for (Section section : sections) {
            if (section.offset() > offset) {
                break;
            }
            path = section.path();
        }
        return path;
    }

    /**
     * 一块文本属于哪一章。
     *
     * <p>先用"块内出现的最后一个章节起点"，没有再回退到"块起点所属章节"。
     *
     * <p>为什么不直接用块起点：切块器常把标题行和它下面的正文放在同一块里，甚至整篇短文档
     * 切成一块——那一块的起点在第一个 {@code ##} 之前，按起点定位会得到空章节，前缀只剩文档标题，
     * 而这一块的内容明明全在某一章下面。
     *
     * @param sections {@link #sections} 的结果
     * @param body     块文本
     * @param at       块在原文中的起始下标
     * @return 章节链，可能为空
     */
    static String sectionOf(List<Section> sections, String body, int at) {
        // 先按"块里出现了哪个章节标题的文字"：切块器拼接句子时会删掉换行，
        // 块文本因此常常不是原文的连续子串，按位置找会失败（实测短文档被切成一块时必失败）。
        String byTitle = "";
        for (Section section : sections) {
            if (body.contains(section.title())) {
                byTitle = section.path();
            }
        }
        if (!byTitle.isEmpty()) {
            return byTitle;
        }
        if (at < 0) {
            return "";
        }
        // 块里一个标题都没有（从章节中间开始的块）：回到"块起点所属章节"。
        String inside = "";
        int end = at + body.length();
        for (Section section : sections) {
            if (section.offset() >= end) {
                break;
            }
            if (section.offset() >= at) {
                inside = section.path();
            }
        }
        return inside.isEmpty() ? at(sections, at) : inside;
    }

    /**
     * 拼块前缀：{@code 【文档标题 › 章节路径】} 加一个换行。
     *
     * <p>章节路径为空（块在第一条标题之前）时只留标题。超过 {@link #MAX_PREFIX_CHARS} 时截断并
     * 加省略号——保留原文比保留一个长标题重要。
     *
     * @param title   文档标题
     * @param section 章节链，可以为空
     * @return 以换行结尾的前缀，可以直接拼在块文本前面
     */
    static String prefix(String title, String section) {
        String body = section == null || section.isBlank() ? title : title + SEPARATOR + section;
        String head = "【" + body + "】";
        if (head.length() > MAX_PREFIX_CHARS) {
            head = head.substring(0, MAX_PREFIX_CHARS - 2) + "…】";
        }
        return head + "\n";
    }

    /** 文件名去掉扩展名，下划线与连字符换成空格：{@code after-sales.md} → {@code after sales}。 */
    private static String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "未命名文档";
        }
        String name = path;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name.replace('-', ' ').replace('_', ' ').strip();
    }

    /**
     * 取栈里最深的至多三级，用 {@code ›} 连起来。
     *
     * <p>跳过一级标题：它就是文档标题，{@link #prefix} 已经拼在最前面了。不跳的话会得到
     * {@code 【售后政策 › 售后政策 › 第二章】}——同一份文档名出现两次，读起来像 bug。
     *
     * <p>只剩一级标题时返回空串，前缀退化成"只有文档标题"，那正是对的：这篇文档没有分章。
     */
    private static String pathOf(List<Heading> stack) {
        List<Heading> sections = stack.stream().filter(heading -> heading.level() > 1).toList();
        int from = Math.max(0, sections.size() - MAX_PATH_DEPTH);
        StringBuilder path = new StringBuilder();
        for (int i = from; i < sections.size(); i++) {
            if (path.length() > 0) {
                path.append(SEPARATOR);
            }
            path.append(sections.get(i).text());
        }
        return path.toString();
    }

    /**
     * 扫出所有 ATX 标题，跳过围栏代码块。
     *
     * <p>按 {@code \n} 切并保留空行（{@code -1}），这样下标与原文一致——块是靠
     * {@code content.indexOf(块文本)} 定位的，下标差一个字符，章节就会整体错位一章。
     *
     * <p>换行符按 {@code \n} 一个字符计：CRLF 文件里 {@code \r} 留在行尾，被 {@code strip()} 去掉，
     * 但 {@code line.length()} 含它，所以下标仍然对得上。
     */
    private static List<Heading> headings(String content) {
        List<Heading> headings = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return headings;
        }
        int offset = 0;
        boolean fenced = false;
        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                fenced = !fenced;
            }
            else if (!fenced) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                    level++;
                }
                if (level >= 1 && level <= 6) {
                    String text = trimmed.substring(level).strip();
                    if (!text.isEmpty()) {
                        headings.add(new Heading(offset, level, text));
                    }
                }
            }
            offset += line.length() + 1;
        }
        return headings;
    }
}
