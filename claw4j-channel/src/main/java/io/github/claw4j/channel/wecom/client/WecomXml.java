package io.github.claw4j.channel.wecom.client;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 企业微信的报文是 XML，这里只做一件极小的事：<b>把根节点下面那一层子元素读成一张表</b>。
 *
 * <p>不做 XSD 校验、不做嵌套、不建对象树。报文就两种、字段都是平的
 * （外层 {@code ToUserName / Encrypt / AgentID}，内层 {@code FromUserName / MsgType / Content / MsgId …}），
 * 多出来的结构只会让人以为这里在做一个通用的 XML 绑定层。
 *
 * <p><b>XXE 必须在这里就关掉</b>：这是一个<b>匿名可访问</b>的 HTTP 端点，而 JDK 的
 * {@link DocumentBuilderFactory} 默认允许外部实体——不关的话，一个带 {@code <!DOCTYPE>}
 * 的请求就能让服务端去读本机文件、或者去连一台内网主机，然后把内容带回响应里。
 * 五条设置对着五种攻击面，少一条都留着口子（{@code disallow-doctype-decl} 一条就够，
 * 其余四条是纵深：万一哪天有人把它删了，其它几条还挡着实体展开）。
 *
 * <p>不带命名空间、也不解析注释：企业微信的报文没有这些，"支持"它们只会多两条没人走的路径。
 */
final class WecomXml {

    private WecomXml() {
    }

    /**
     * @return 根元素的直接子元素：标签名 → 文本（{@code CDATA} 已经是文本，不需要额外处理）
     * @throws IllegalStateException 不是合法 XML，或根节点下没有元素
     */
    static Map<String, String> childrenOf(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalStateException("报文是空的");
        }
        try {
            DocumentBuilder builder = factory().newDocumentBuilder();
            Document document = builder.parse(new InputSource(new StringReader(xml)));
            Element root = document.getDocumentElement();
            if (root == null) {
                throw new IllegalStateException("报文里没有根元素");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            NodeList children = root.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                Node child = children.item(index);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    fields.put(child.getNodeName(), child.getTextContent());
                }
            }
            return fields;
        } catch (IllegalStateException rejected) {
            throw rejected;
        } catch (Exception failure) {
            throw new IllegalStateException("报文不是合法 XML：" + failure.getClass().getSimpleName(), failure);
        }
    }

    private static DocumentBuilderFactory factory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        return factory;
    }
}
