/**
 * 检索前改写：把用户问题换成更适合检索的问法。
 *
 * <p>改写本身交给框架的 {@code RewriteQueryTransformer}；本包只做三件防护——超时、该不该改写、
 * 改写后关键标识有没有丢。防护与改写分开，换模型不用碰防护。
 */
package io.github.claw4j.rag.rewrite;
