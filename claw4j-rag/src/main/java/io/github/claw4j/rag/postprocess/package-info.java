/**
 * 候选的后处理：重排、截断、父块回填。
 *
 * <p>三者都实现 {@code DocumentPostProcessor}，作用在"已经召回到的一组块"上，不参与召回本身。
 * 顺序由装配类决定，每个类只改自己那一件事。
 */
package io.github.claw4j.rag.postprocess;
