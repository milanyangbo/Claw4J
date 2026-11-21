/**
 * 读取侧：把一个问题变成一组候选块。
 *
 * <p>两路召回（向量 + 关键字）、融合打分、按引擎选实现都在这里。本包只产出候选，
 * 不负责给模型拼提示词——那是装配类与 advisor 的事。
 */
package io.github.claw4j.rag.retrieval;
