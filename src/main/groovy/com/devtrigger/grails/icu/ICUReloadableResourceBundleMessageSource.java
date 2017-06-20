package com.devtrigger.grails.icu;

import com.ibm.icu.text.MessageFormat;
import grails.util.CacheEntry;
import grails.util.Pair;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.Assert;
import org.springframework.util.DefaultPropertiesPersister;
import org.springframework.util.PropertiesPersister;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ICU4j MessageFormat aware {@link org.springframework.context.support.ReloadableResourceBundleMessageSource}
 * drop-in
 * @see com.ibm.icu.text.MessageFormat
 */
public class ICUReloadableResourceBundleMessageSource extends ICUAbstractMessageSource
        implements ResourceLoaderAware {


    private static final String PROPERTIES_SUFFIX = ".properties";

    private static final String XML_SUFFIX = ".xml";


    private String[] basenames = new String[0];

    private String defaultEncoding;

    private Properties fileEncodings;

    private boolean fallbackToSystemLocale = true;

    protected long cacheMillis = -1;

    protected long fileCacheMillis = Long.MIN_VALUE;

    private PropertiesPersister propertiesPersister = new DefaultPropertiesPersister();

    private ResourceLoader resourceLoader = new DefaultResourceLoader();

    /** Cache to hold filename lists per Locale */
    private final ConcurrentMap<Pair<String, Locale>, CacheEntry<List<Pair<String, Resource>>>> cachedFilenames =
            new ConcurrentHashMap<Pair<String, Locale>, CacheEntry<List<Pair<String, Resource>>>>();

    /** Cache to hold already loaded properties per filename */
    private final ConcurrentMap<String, CacheEntry<ICUReloadableResourceBundleMessageSource.PropertiesHolder>> cachedProperties = new ConcurrentHashMap<String, CacheEntry<ICUReloadableResourceBundleMessageSource.PropertiesHolder>>();

    /** Cache to hold merged loaded properties per locale */
    private final ConcurrentMap<Locale, CacheEntry<ICUReloadableResourceBundleMessageSource.PropertiesHolder>> cachedMergedProperties = new ConcurrentHashMap<Locale, CacheEntry<ICUReloadableResourceBundleMessageSource.PropertiesHolder>>();

    private final ConcurrentMap<String, CacheEntry<Resource>> cachedResources = new ConcurrentHashMap<String, CacheEntry<Resource>>();


    /**
     * Set a single basename, following the basic ResourceBundle convention of
     * not specifying file extension or language codes, but in contrast to
     * {@link ResourceBundleMessageSource} referring to a Spring resource location:
     * e.g. "WEB-INF/messages" for "WEB-INF/messages.properties",
     * "WEB-INF/messages_en.properties", etc.
     * <p>XML properties files are also supported: .g. "WEB-INF/messages" will find
     * and load "WEB-INF/messages.xml", "WEB-INF/messages_en.xml", etc as well.
     * @param basename the single basename
     * @see #setBasenames
     * @see org.springframework.core.io.ResourceEditor
     * @see java.util.ResourceBundle
     */
    public void setBasename(String basename) {
        setBasenames(basename);
    }

    /**
     * Set an array of basenames, each following the basic ResourceBundle convention
     * of not specifying file extension or language codes, but in contrast to
     * {@link ResourceBundleMessageSource} referring to a Spring resource location:
     * e.g. "WEB-INF/messages" for "WEB-INF/messages.properties",
     * "WEB-INF/messages_en.properties", etc.
     * <p>XML properties files are also supported: .g. "WEB-INF/messages" will find
     * and load "WEB-INF/messages.xml", "WEB-INF/messages_en.xml", etc as well.
     * <p>The associated resource bundles will be checked sequentially when resolving
     * a message code. Note that message definitions in a <i>previous</i> resource
     * bundle will override ones in a later bundle, due to the sequential lookup.
     * @param basenames an array of basenames
     * @see #setBasename
     * @see java.util.ResourceBundle
     */
    public void setBasenames(String... basenames) {
        if (basenames != null) {
            this.basenames = new String[basenames.length];
            for (int i = 0; i < basenames.length; i++) {
                String basename = basenames[i];
                Assert.hasText(basename, "Basename must not be empty");
                this.basenames[i] = basename.trim();
            }
        }
        else {
            this.basenames = new String[0];
        }
    }

    /**
     * Set the default charset to use for parsing properties files.
     * Used if no file-specific charset is specified for a file.
     * <p>Default is none, using the {@code java.util.Properties}
     * default encoding: ISO-8859-1.
     * <p>Only applies to classic properties files, not to XML files.
     * @param defaultEncoding the default charset
     * @see #setFileEncodings
     * @see org.springframework.util.PropertiesPersister#load
     */
    public void setDefaultEncoding(String defaultEncoding) {
        this.defaultEncoding = defaultEncoding;
    }

    /**
     * Set per-file charsets to use for parsing properties files.
     * <p>Only applies to classic properties files, not to XML files.
     * @param fileEncodings Properties with filenames as keys and charset
     * names as values. Filenames have to match the basename syntax,
     * with optional locale-specific appendices: e.g. "WEB-INF/messages"
     * or "WEB-INF/messages_en".
     * @see #setBasenames
     * @see org.springframework.util.PropertiesPersister#load
     */
    public void setFileEncodings(Properties fileEncodings) {
        this.fileEncodings = fileEncodings;
    }

    /**
     * Set whether to fall back to the system Locale if no files for a specific
     * Locale have been found. Default is "true"; if this is turned off, the only
     * fallback will be the default file (e.g. "messages.properties" for
     * basename "messages").
     * <p>Falling back to the system Locale is the default behavior of
     * {@code java.util.ResourceBundle}. However, this is often not desirable
     * in an application server environment, where the system Locale is not relevant
     * to the application at all: Set this flag to "false" in such a scenario.
     */
    public void setFallbackToSystemLocale(boolean fallbackToSystemLocale) {
        this.fallbackToSystemLocale = fallbackToSystemLocale;
    }

    /**
     * Set the number of seconds to cache the list of matching properties files.
     * <ul>
     * <li>Default is "-1", indicating to cache forever (just like
     * {@code java.util.ResourceBundle}).
     * <li>A positive number will cache the list of matching properties files for the given
     * number of seconds. This is essentially the interval between refresh checks.
     * <li>A value of "0" will attemp to list the matching properties files on
     * every message access. <b>Do not use this in a production environment!</b>
     * </ul>
     */
    public void setCacheSeconds(int cacheSeconds) {
        this.cacheMillis = (cacheSeconds * 1000);
        if(fileCacheMillis==Long.MIN_VALUE) {
            this.fileCacheMillis = this.cacheMillis;
        }
    }

    /**
     * Set the number of seconds to cache loaded properties files.
     * <ul>
     * <li>Default value is the same value as cacheSeconds
     * <li>A positive number will cache loaded properties files for the given
     * number of seconds. This is essentially the interval between refresh checks.
     * Note that a refresh attempt will first check the last-modified timestamp
     * of the file before actually reloading it; so if files don't change, this
     * interval can be set rather low, as refresh attempts will not actually reload.
     * <li>A value of "0" will check the last-modified timestamp of the file on
     * every message access. <b>Do not use this in a production environment!</b>
     * </ul>
     */
    public void setFileCacheSeconds(int fileCacheSeconds) {
        this.fileCacheMillis = (fileCacheSeconds * 1000);
    }

    /**
     * Set the PropertiesPersister to use for parsing properties files.
     * <p>The default is a DefaultPropertiesPersister.
     * @see org.springframework.util.DefaultPropertiesPersister
     */
    public void setPropertiesPersister(PropertiesPersister propertiesPersister) {
        this.propertiesPersister =
                (propertiesPersister != null ? propertiesPersister : new DefaultPropertiesPersister());
    }

    /**
     * Set the ResourceLoader to use for loading bundle properties files.
     * <p>The default is a DefaultResourceLoader. Will get overridden by the
     * ApplicationContext if running in a context, as it implements the
     * ResourceLoaderAware interface. Can be manually overridden when
     * running outside of an ApplicationContext.
     * @see org.springframework.core.io.DefaultResourceLoader
     * @see org.springframework.context.ResourceLoaderAware
     */
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = (resourceLoader != null ? resourceLoader : new DefaultResourceLoader());
    }


    /**
     * Resolves the given message code as key in the retrieved bundle files,
     * returning the value found in the bundle as-is (without MessageFormat parsing).
     */
    @Override
    protected String resolveCodeWithoutArguments(String code, Locale locale) {
        if (this.cacheMillis < 0) {
            ICUReloadableResourceBundleMessageSource.PropertiesHolder propHolder = getMergedProperties(locale);
            String result = propHolder.getProperty(code);
            if (result != null) {
                return result;
            }
        }
        else {
            for (String basename : this.basenames) {
                List<Pair<String, Resource>> filenamesAndResources = calculateAllFilenames(basename, locale);
                for (Pair<String, Resource> filenameAndResource : filenamesAndResources) {
                    if(filenameAndResource.getbValue() != null) {
                        ICUReloadableResourceBundleMessageSource.PropertiesHolder propHolder = getProperties(filenameAndResource.getaValue(), filenameAndResource.getbValue());
                        String result = propHolder.getProperty(code);
                        if (result != null) {
                            return result;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Resolves the given message code as key in the retrieved bundle files,
     * using a cached MessageFormat instance per message code.
     */
    @Override
    protected MessageFormat resolveCode(String code, Locale locale) {
        if (this.cacheMillis < 0) {
            ICUReloadableResourceBundleMessageSource.PropertiesHolder propHolder = getMergedProperties(locale);
            MessageFormat result = propHolder.getMessageFormat(code, locale);
            if (result != null) {
                return result;
            }
        }
        else {
            for (String basename : this.basenames) {
                List<Pair<String, Resource>> filenamesAndResources = calculateAllFilenames(basename, locale);
                for (Pair<String, Resource> filenameAndResource : filenamesAndResources) {
                    if(filenameAndResource.getbValue() != null) {
                        ICUReloadableResourceBundleMessageSource.PropertiesHolder propHolder = getProperties(filenameAndResource.getaValue(), filenameAndResource.getbValue());
                        MessageFormat result = propHolder.getMessageFormat(code, locale);
                        if (result != null) {
                            return result;
                        }
                    }
                }
            }
        }
        return null;
    }


    /**
     * Get a PropertiesHolder that contains the actually visible properties
     * for a Locale, after merging all specified resource bundles.
     * Either fetches the holder from the cache or freshly loads it.
     * <p>Only used when caching resource bundle contents forever, i.e.
     * with cacheSeconds < 0. Therefore, merged properties are always
     * cached forever.
     */
    protected ICUReloadableResourceBundleMessageSource.PropertiesHolder getMergedProperties(final Locale locale) {
        return CacheEntry.getValue(cachedMergedProperties, locale, cacheMillis, new Callable<ICUReloadableResourceBundleMessageSource.PropertiesHolder>() {
            @Override
            public ICUReloadableResourceBundleMessageSource.PropertiesHolder call() throws Exception {
                Properties mergedProps = new Properties();
                ICUReloadableResourceBundleMessageSource.PropertiesHolder mergedHolder = new ICUReloadableResourceBundleMessageSource.PropertiesHolder(mergedProps);
                for (int i = basenames.length - 1; i >= 0; i--) {
                    List<Pair<String, Resource>> filenamesAndResources = calculateAllFilenames(basenames[i], locale);
                    for (int j = filenamesAndResources.size() - 1; j >= 0; j--) {
                        Pair<String, Resource> filenameAndResource = filenamesAndResources.get(j);
                        if(filenameAndResource.getbValue() != null) {
                            ICUReloadableResourceBundleMessageSource.PropertiesHolder propHolder = getProperties(filenameAndResource.getaValue(), filenameAndResource.getbValue());
                            mergedProps.putAll(propHolder.getProperties());
                        }
                    }
                }
                return mergedHolder;
            }
        });
    }

    /**
     * Calculate all filenames for the given bundle basename and Locale.
     * Will calculate filenames for the given Locale, the system Locale
     * (if applicable), and the default file.
     * @param basename the basename of the bundle
     * @param locale the locale
     * @return the List of filenames to check
     * @see #setFallbackToSystemLocale
     * @see #calculateFilenamesForLocale
     */
    protected List<Pair<String, Resource>> calculateAllFilenames(final String basename, final Locale locale) {
        Pair<String, Locale> cacheKey = new Pair<String, Locale>(basename, locale);
        return CacheEntry.getValue(cachedFilenames, cacheKey, cacheMillis, new Callable<List<Pair<String, Resource>>>() {
            @Override
            public List<Pair<String, Resource>> call() throws Exception {
                List<String> filenames = new ArrayList<String>(7);
                filenames.addAll(calculateFilenamesForLocale(basename, locale));
                if (fallbackToSystemLocale && !locale.equals(Locale.getDefault())) {
                    List<String> fallbackFilenames = calculateFilenamesForLocale(basename, Locale.getDefault());
                    for (String fallbackFilename : fallbackFilenames) {
                        if (!filenames.contains(fallbackFilename)) {
                            // Entry for fallback locale that isn't already in filenames list.
                            filenames.add(fallbackFilename);
                        }
                    }
                }
                filenames.add(basename);
                List<Pair<String, Resource>> filenamesAndResources = new ArrayList<Pair<String,Resource>>(filenames.size());
                for(String filename : filenames) {
                    filenamesAndResources.add(new Pair<String, Resource>(filename, locateResource(filename)));
                }
                return filenamesAndResources;
            }
        });
    }

    /**
     * Calculate the filenames for the given bundle basename and Locale,
     * appending language code, country code, and variant code.
     * E.g.: basename "messages", Locale "de_AT_oo" -> "messages_de_AT_OO",
     * "messages_de_AT", "messages_de".
     * <p>Follows the rules defined by {@link java.util.Locale#toString()}.
     * @param basename the basename of the bundle
     * @param locale the locale
     * @return the List of filenames to check
     */
    protected List<String> calculateFilenamesForLocale(String basename, Locale locale) {
        List<String> result = new ArrayList<String>(3);
        String language = locale.getLanguage();
        String country = locale.getCountry();
        String variant = locale.getVariant();
        StringBuilder temp = new StringBuilder(basename);

        temp.append('_');
        if (language.length() > 0) {
            temp.append(language);
            result.add(0, temp.toString());
        }

        temp.append('_');
        if (country.length() > 0) {
            temp.append(country);
            result.add(0, temp.toString());
        }

        if (variant.length() > 0 && (language.length() > 0 || country.length() > 0)) {
            temp.append('_').append(variant);
            result.add(0, temp.toString());
        }

        return result;
    }


    /**
     * Get a PropertiesHolder for the given filename, either from the
     * cache or freshly loaded.
     * @param filename the bundle filename (basename + Locale)
     * @return the current PropertiesHolder for the bundle
     */
    @SuppressWarnings("rawtypes")
    protected ICUReloadableResourceBundleMessageSource.PropertiesHolder getProperties(final String filename, final Resource resource) {
        return CacheEntry.getValue(cachedProperties, filename, fileCacheMillis, new Callable<ICUReloadableResourceBundleMessageSource.PropertiesHolder>() {
            @Override
            public ICUReloadableResourceBundleMessageSource.PropertiesHolder call() throws Exception {
                return new ICUReloadableResourceBundleMessageSource.PropertiesHolder(filename, resource);
            }
        }, new Callable<CacheEntry>() {
            @Override
            public CacheEntry call() throws Exception {
                return new ICUReloadableResourceBundleMessageSource.PropertiesHolderCacheEntry();
            }
        }, true, null);
    }

    protected static class PropertiesHolderCacheEntry extends CacheEntry<ICUReloadableResourceBundleMessageSource.PropertiesHolder> {
        public PropertiesHolderCacheEntry() {
            super();
        }

        @Override
        protected ICUReloadableResourceBundleMessageSource.PropertiesHolder updateValue(ICUReloadableResourceBundleMessageSource.PropertiesHolder oldValue, Callable<ICUReloadableResourceBundleMessageSource.PropertiesHolder> updater, Object cacheRequestObject)
                throws Exception {
            if(oldValue != null) {
                oldValue.update();
                return oldValue;
            }
            return updater.call();
        }
    }


    /**
     * Load the properties from the given resource.
     * @param resource the resource to load from
     * @param filename the original bundle filename (basename + Locale)
     * @return the populated Properties instance
     * @throws IOException if properties loading failed
     */
    protected Properties loadProperties(Resource resource, String filename) throws IOException {
        InputStream is = resource.getInputStream();
        Properties props = new Properties();
        try {
            if (resource.getFilename().endsWith(XML_SUFFIX)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Loading properties [" + resource.getFilename() + "]");
                }
                this.propertiesPersister.loadFromXml(props, is);
            }
            else {
                String encoding = null;
                if (this.fileEncodings != null) {
                    encoding = this.fileEncodings.getProperty(filename);
                }
                if (encoding == null) {
                    encoding = this.defaultEncoding;
                }
                if (encoding != null) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Loading properties [" + resource.getFilename() + "] with encoding '" + encoding + "'");
                    }
                    this.propertiesPersister.load(props, new InputStreamReader(is, encoding));
                }
                else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Loading properties [" + resource.getFilename() + "]");
                    }
                    this.propertiesPersister.load(props, is);
                }
            }
            return props;
        }
        finally {
            is.close();
        }
    }


    /**
     * Clear the resource bundle cache.
     * Subsequent resolve calls will lead to reloading of the properties files.
     */
    public void clearCache() {
        logger.debug("Clearing entire resource bundle cache");
        this.cachedProperties.clear();
        this.cachedMergedProperties.clear();
        this.cachedFilenames.clear();
        this.cachedResources.clear();
    }

    /**
     * Clear the resource bundle caches of this MessageSource and all its ancestors.
     * @see #clearCache
     */
    public void clearCacheIncludingAncestors() {
        clearCache();
        if (getParentMessageSource() instanceof ICUReloadableResourceBundleMessageSource) {
            ((ICUReloadableResourceBundleMessageSource) getParentMessageSource()).clearCacheIncludingAncestors();
        } else if (getParentMessageSource() instanceof ICUReloadableResourceBundleMessageSource) {
            ((ICUReloadableResourceBundleMessageSource) getParentMessageSource()).clearCacheIncludingAncestors();
        }
    }

    @Override
    public String toString() {
        return getClass().getName() + ": basenames=[" + StringUtils.arrayToCommaDelimitedString(this.basenames) + "]";
    }

    protected Resource locateResource(final String filename) {
        return CacheEntry.getValue(cachedResources, filename, cacheMillis, new Callable<Resource>() {
            @Override
            public Resource call() throws Exception {
                return locateResourceWithoutCache(filename);
            }
        });
    }

    protected Resource locateResourceWithoutCache(String filename) {
        Resource resource = resourceLoader.getResource(org.grails.io.support.ResourceLoader.CLASSPATH_URL_PREFIX + filename + PROPERTIES_SUFFIX);
        if(!resource.exists()) {
            resource = resourceLoader.getResource(filename + PROPERTIES_SUFFIX);
        }
        if (!resource.exists()) {
            resource = resourceLoader.getResource(filename + XML_SUFFIX);
        }
        if (resource.exists()) {
            return resource;
        } else {
            return null;
        }
    }

    /**
     * PropertiesHolder for caching.
     * Stores the last-modified timestamp of the source file for efficient
     * change detection, and the timestamp of the last refresh attempt
     * (updated every time the cache entry gets re-validated).
     */
    protected class PropertiesHolder {
        private Properties properties;

        private String filename;
        private Resource resource;

        private long fileTimestamp = -1;

        /** Cache to hold already generated MessageFormats per message code */
        private final ConcurrentMap<Pair<String, Locale>, CacheEntry<MessageFormat>> cachedMessageFormats =
                new ConcurrentHashMap<Pair<String, Locale>, CacheEntry<MessageFormat>>();

        public PropertiesHolder(String filename, Resource resource) {
            this.filename = filename;
            this.resource = resource;
            doUpdate(true);
        }

        public PropertiesHolder(Properties properties) {
            this.properties = properties;
        }

        public boolean update() {
            return doUpdate(false);
        }

        private boolean doUpdate(boolean initialization) {
            if(filename == null) {
                return false;
            }
            if(!initialization && cacheMillis >= 0) {
                resource = locateResource(filename);
            }
            if(resource != null) {
                long newFileTimestamp;
                try {
                    newFileTimestamp = resource.lastModified();
                } catch (IOException ex) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(
                                resource + " could not be resolved in the file system - assuming that is hasn't changed", ex);
                    }
                    newFileTimestamp = -1;
                }
                if (fileCacheMillis >= 0 && newFileTimestamp == fileTimestamp && this.properties != null) {
                    return false;
                }
                try {
                    this.properties = loadProperties(resource, filename);
                    this.fileTimestamp = newFileTimestamp;
                    this.cachedMessageFormats.clear();
                }
                catch (IOException ex) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Could not parse properties file [" + resource.getFilename() + "]", ex);
                    }
                }
                return true;
            }
            else {
                // Resource does not exist.
                if (logger.isDebugEnabled()) {
                    logger.debug("No properties file found for [" + filename + "] - neither plain properties nor XML");
                }
                this.properties = new Properties();
                this.fileTimestamp = -1;
                this.cachedMessageFormats.clear();
                return true;
            }
        }

        public String getFilename() {
            return filename;
        }

        public Properties getProperties() {
            return properties;
        }

        public long getFileTimestamp() {
            return fileTimestamp;
        }

        public String getProperty(String code) {
            if (this.properties == null) {
                return null;
            }
            return this.properties.getProperty(code);
        }

        public MessageFormat getMessageFormat(final String code, final Locale locale) {
            if (this.properties == null) {
                return null;
            }
            Pair<String, Locale> cacheKey = new Pair<String, Locale>(code, locale);
            return CacheEntry.getValue(cachedMessageFormats, cacheKey, -1, new Callable<MessageFormat>() {
                @Override
                public MessageFormat call() throws Exception {
                    String msg = properties.getProperty(code);
                    if (msg != null) {
                        return createMessageFormat(msg, locale);
                    } else {
                        return null;
                    }
                }
            });
        }
    }

}
