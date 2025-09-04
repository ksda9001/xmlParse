package net.stjconnector;

import net.stjconnector.exception.XmlProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * XML读取工具类
 * 用于从XML文件中读取指定标签的内容
 */
public class XmlUtil {
    private static final Logger logger = LoggerFactory.getLogger(XmlUtil.class);
    
    // XML解析器功能常量
    private static final String FEATURE_DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String FEATURE_EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String FEATURE_EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities";
    
    // 缓存DocumentBuilderFactory实例，因为它的创建成本很高
    private static final AtomicReference<DocumentBuilderFactory> FACTORY_CACHE = new AtomicReference<>();
    
    // 缓存TransformerFactory实例
    private static final AtomicReference<TransformerFactory> TRANSFORMER_FACTORY_CACHE = new AtomicReference<>();
    
    // 缓存常用的Transformer实例，使用ConcurrentHashMap确保线程安全
    private static final ConcurrentHashMap<String, Transformer> TRANSFORMER_CACHE = new ConcurrentHashMap<>();
    
    // 预估的默认StringBuilder容量
    private static final int DEFAULT_BUFFER_SIZE = 4096;
    
    /**
     * 初始化并返回一个安全配置的DocumentBuilderFactory
     * @return 配置好的DocumentBuilderFactory实例
     * @throws XmlProcessingException 如果配置失败
     */
    private static DocumentBuilderFactory getSecureDocumentBuilderFactory() throws XmlProcessingException {
        DocumentBuilderFactory factory = FACTORY_CACHE.get();
        if (factory == null) {
            factory = DocumentBuilderFactory.newInstance();
            try {
                // 设置基本安全特性
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setNamespaceAware(true);
                factory.setExpandEntityReferences(false);
                factory.setXIncludeAware(false);
                
                // 尝试设置额外的安全特性
                trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
                trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
                trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
                
                // 设置属性以禁用外部DTD和实体
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                
                // 禁用DOCTYPE声明
                factory.setValidating(false);
                
                // 尝试原子性地设置缓存
                if (!FACTORY_CACHE.compareAndSet(null, factory)) {
                    factory = FACTORY_CACHE.get();
                }
            } catch (Exception e) {
                throw new XmlProcessingException("无法配置安全的XML解析器", e);
            }
        }
        return factory;
    }
    
    /**
     * 获取配置好的TransformerFactory实例
     * @return TransformerFactory实例
     * @throws XmlProcessingException 如果配置失败
     */
    private static TransformerFactory getSecureTransformerFactory() throws XmlProcessingException {
        TransformerFactory factory = TRANSFORMER_FACTORY_CACHE.get();
        if (factory == null) {
            factory = TransformerFactory.newInstance();
            try {
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
                
                if (!TRANSFORMER_FACTORY_CACHE.compareAndSet(null, factory)) {
                    factory = TRANSFORMER_FACTORY_CACHE.get();
                }
            } catch (Exception e) {
                throw new XmlProcessingException("无法配置安全的转换器工厂", e);
            }
        }
        return factory;
    }

    /**
     * 从XML文件中读取指定标签的完整内容（包括所有子标签，但不包括标签本身）
     *
     * @param filePath XML文件路径
     * @param tagName  要读取的标签名称
     * @return 标签内的完整XML内容（不包括标签本身，但包括所有子标签）
     * @throws XmlProcessingException 解析异常
     */
    public static String readElementTextFromFile(String filePath, String tagName) throws XmlProcessingException {
        if (filePath == null || tagName == null) {
            throw new XmlProcessingException("文件路径和标签名称不能为空");
        }

        File file = new File(filePath);
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            throw new XmlProcessingException("无法访问XML文件: " + filePath);
        }

        try {
            DocumentBuilder builder = getSecureDocumentBuilderFactory().newDocumentBuilder();
            Document document = builder.parse(file);
            
            // 规范化文档，这个操作会合并相邻的文本节点并删除空的文本节点
            document.getDocumentElement().normalize();

            NodeList nodeList = document.getElementsByTagName(tagName);
            if (nodeList.getLength() == 0) {
                logger.debug("未找到标签 '{}' 在文件 '{}'中", tagName, filePath);
                return null;
            }

            // 获取第一个匹配的节点
            Node node = nodeList.item(0);
            return innerNodeToString(node);
        } catch (XmlProcessingException e) {
            throw e;
        } catch (Exception e) {
            logger.error("解析XML文件失败: {} 标签: {}", filePath, tagName, e);
            throw new XmlProcessingException("解析XML文件失败: " + filePath + " 标签: " + tagName, e);
        }
    }
    
    /**
     * 将Node节点的子节点转换为字符串
     *
     * @param node 要转换的节点
     * @return 节点内子节点的XML字符串表示
     * @throws Exception 转换异常
     */
    private static String innerNodeToString(Node node) throws Exception {
        if (node == null) {
            return "";
        }

        // 预估子节点数量来初始化StringBuilder的容量
        int estimatedLength = estimateContentLength(node);
        StringBuilder result = new StringBuilder(Math.max(estimatedLength, DEFAULT_BUFFER_SIZE));
        NodeList children = node.getChildNodes();
        
        // 获取或创建缓存的Transformer
        Transformer transformer = getTransformer();
        
        // 遍历所有子节点
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            try {
                switch (child.getNodeType()) {
                    case Node.TEXT_NODE:
                        // 文本节点直接添加内容
                        String text = child.getTextContent();
                        if (text != null) {
                            text = text.trim();
                            if (!text.isEmpty()) {
                                result.append(text);
                            }
                        }
                        break;
                    case Node.ELEMENT_NODE:
                        // 元素节点需要转换为XML字符串
                        try (StringWriter writer = new StringWriter(estimatedLength / 2)) {
                            transformer.transform(new DOMSource(child), new StreamResult(writer));
                            result.append(writer.toString());
                        }
                        break;
                    case Node.CDATA_SECTION_NODE:
                        // 处理CDATA节点
                        String cdataContent = child.getTextContent();
                        if (cdataContent != null) {
                            result.append(cdataContent);
                        }
                        break;
                }
            } catch (Exception e) {
                logger.warn("处理节点时发生错误: {}", e.getMessage());
                // 继续处理其他节点
            }
        }
        return result.toString();
    }
    
    /**
     * 估算节点内容的长度，用于优化StringBuilder的初始容量
     */
    private static int estimateContentLength(Node node) {
        int estimate = 0;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                estimate += child.getTextContent() != null ? child.getTextContent().length() : 0;
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                // 对于元素节点，估算标签名长度和属性长度
                estimate += child.getNodeName().length() * 2 + 5; // 为开闭标签预留空间
                estimate += estimateContentLength(child); // 递归估算子内容
            }
        }
        return estimate;
    }
    
    /**
     * 获取或创建缓存的Transformer实例
     */
    /**
     * 尝试设置XML解析器的功能特性，如果不支持则记录警告
     * @param factory XML解析器工厂
     * @param feature 功能特性名称
     * @param value 特性值
     */
    private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception e) {
            logger.warn("XML解析器不支持功能: {}，这可能会降低安全性", feature);
        }
    }

    /**
     * 获取或创建缓存的Transformer实例
     */
    private static Transformer getTransformer() throws Exception {
        String key = "default";
        return TRANSFORMER_CACHE.computeIfAbsent(key, k -> {
            try {
                Transformer transformer = getSecureTransformerFactory().newTransformer();
                transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
                transformer.setOutputProperty(OutputKeys.INDENT, "no");
                transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
                return transformer;
            } catch (Exception e) {
                throw new RuntimeException("无法创建Transformer实例", e);
            }
        });
    }
    }
}