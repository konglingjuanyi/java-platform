package com.whenling.module.web.converter;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractGenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.serializer.JSONSerializer;
import com.alibaba.fastjson.serializer.PropertyFilter;
import com.alibaba.fastjson.serializer.PropertyPreFilter;
import com.alibaba.fastjson.serializer.SerializeConfig;
import com.alibaba.fastjson.serializer.SerializeWriter;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.google.common.collect.Sets;
import com.whenling.module.base.util.PropertyUtil;
import com.whenling.module.domain.json.IncludesPropertyPreFilter;
import com.whenling.module.domain.model.BaseEntity;
import com.whenling.module.domain.model.BizEntity;
import com.whenling.module.domain.model.Node;
import com.whenling.module.domain.model.Tree;

/**
 * fastjson转换器
 * 
 * @作者 孔祥溪
 * @博客 http://ken.whenling.com
 * @创建时间 2016年3月1日 下午4:52:13
 */
public class FastjsonHttpMessageConverter extends AbstractGenericHttpMessageConverter<Object> {

	public static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

	private SerializeConfig serializeConfig;

	private ParserConfig parserConfig;

	private static final PropertyFilter DEFAULT_PROPERTY_FILTER = new PropertyFilter() {
		@Override
		public boolean apply(Object object, String name, Object value) {
			if (object instanceof BizEntity) {
				if (ArrayUtils.contains(
						new String[] { "createdBy", "createdDate", "lastModifiedBy", "lastModifiedDate" }, name)) {
					return false;
				}
			}
			return true;
		}
	};

	public FastjsonHttpMessageConverter(SerializeConfig serializeConfig, ParserConfig parserConfig) {
		super(MediaType.APPLICATION_JSON_UTF8, new MediaType("application", "*+json", DEFAULT_CHARSET));
		this.serializeConfig = serializeConfig;
		this.parserConfig = parserConfig;
	}

	@Override
	public Object read(Type type, Class<?> contextClass, HttpInputMessage inputMessage)
			throws IOException, HttpMessageNotReadableException {
		String json = IOUtils.toString(inputMessage.getBody(), getCharset(inputMessage.getHeaders()));
		DefaultJSONParser parser = new DefaultJSONParser(json, this.parserConfig);
		Object value = parser.parseObject(type);

		parser.handleResovleTask(value);

		parser.close();

		return value;
	}

	@Override
	protected void writeInternal(Object obj, Type type, HttpOutputMessage outputMessage)
			throws IOException, HttpMessageNotWritableException {
		Charset charset = getCharset(outputMessage.getHeaders());
		OutputStreamWriter out = new OutputStreamWriter(outputMessage.getBody(), charset);
		SerializeWriter writer = new SerializeWriter(out);
		JSONSerializer serializer = new JSONSerializer(writer, this.serializeConfig);
		serializer.config(SerializerFeature.DisableCircularReferenceDetect, true);

		RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
		if (requestAttributes instanceof ServletRequestAttributes) {
			Class<?> rootClass = filterClass(obj);
			if (rootClass != null) {
				String[] paths = ((ServletRequestAttributes) requestAttributes).getRequest()
						.getParameterValues("paths");
				if (paths != null && paths.length > 0) {
					rootClass = ClassUtils.getUserClass(rootClass);
					Map<Class<?>, PropertyPreFilter> propertyFilters = new HashMap<>();
					pathVisit(propertyFilters, Sets.newHashSet(paths), rootClass);

					for (Entry<Class<?>, PropertyPreFilter> entry : propertyFilters.entrySet()) {
						serializer.getPropertyPreFilters().add(entry.getValue());
					}
				} else {

					serializer.getPropertyFilters().add(DEFAULT_PROPERTY_FILTER);
				}
			} else {
				serializer.getPropertyFilters().add(DEFAULT_PROPERTY_FILTER);
			}
		}
		serializer.write(obj);
		writer.flush();
		out.close();
	}

	private void pathVisit(final Map<Class<?>, PropertyPreFilter> propertyFilters, final Set<String> paths,
			Class<?> beanClass) {
		if (paths == null || beanClass == null) {
			return;
		}

		Set<String> revisePaths = new HashSet<>();
		Map<String, Set<String>> references = new HashMap<>();
		for (String path : paths) {
			if (StringUtils.contains(path, ".")) {
				String referenceProperty = StringUtils.substringBefore(path, ".");
				revisePaths.add(referenceProperty);

				Set<String> referencePaths = references.get(referenceProperty);
				if (referencePaths == null) {
					referencePaths = new HashSet<>();
					references.put(referenceProperty, referencePaths);
				}
				referencePaths.add(StringUtils.substringAfter(path, "."));
			} else {
				revisePaths.add(path);
			}
		}

		if (revisePaths.size() > 0) {
			PropertyPreFilter preFilter = propertyFilters.get(beanClass);
			if (preFilter != null) {
				if (preFilter instanceof IncludesPropertyPreFilter) {
					((IncludesPropertyPreFilter) preFilter).getIncludes().addAll(revisePaths);
				}
			} else {
				propertyFilters.put(beanClass, new IncludesPropertyPreFilter(beanClass, revisePaths));
			}

		}

		if (references.size() > 0) {
			for (Entry<String, Set<String>> reference : references.entrySet()) {
				pathVisit(propertyFilters, reference.getValue(),
						PropertyUtil.getPropertyType(beanClass, reference.getKey()));
			}
		}
	}

	protected Class<?> filterClass(Object value) {
		if (value == null) {
			return null;
		}

		if (value instanceof Page) {
			return filterClass(((Page<?>) value).getContent());
		}

		if (value instanceof Iterable) {
			Iterator<?> it = ((Iterable<?>) value).iterator();
			if (it.hasNext()) {
				return filterClass(it.next());
			}
		}

		if (value instanceof BaseEntity) {
			return value.getClass();
		}

		if (value instanceof Node) {
			return filterClass(((Node<?>) value).getData());
		}

		if (value instanceof Tree) {
			return filterClass(((Tree<?>) value).getRoots());
		}

		if (value instanceof Map) {
			return ((Map<?, ?>) value).size() > 0 ? filterClass(((Map<?, ?>) value).values().iterator().next()) : null;
		}
		return null;
	}

	@Override
	protected boolean supports(Class<?> clazz) {
		throw new UnsupportedOperationException();
	}

	@Override
	protected Object readInternal(Class<? extends Object> clazz, HttpInputMessage inputMessage)
			throws IOException, HttpMessageNotReadableException {
		return read(clazz, null, inputMessage);
	}

	@Override
	public boolean canRead(Class<?> clazz, MediaType mediaType) {
		return canRead(mediaType);
	}

	@Override
	public boolean canWrite(Class<?> clazz, MediaType mediaType) {
		return canWrite(mediaType);
	}

	private Charset getCharset(HttpHeaders headers) {
		if (headers == null || headers.getContentType() == null || headers.getContentType().getCharSet() == null) {
			return DEFAULT_CHARSET;
		}
		return headers.getContentType().getCharSet();
	}

}
