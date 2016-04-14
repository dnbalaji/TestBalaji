package com.ibm.ewf.advice;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.log4j.Logger;

import com.filenet.api.collection.DateTimeList;
import com.filenet.api.collection.Float64List;
import com.filenet.api.collection.IdList;
import com.filenet.api.collection.Integer32List;
import com.filenet.api.collection.StringList;
import com.filenet.api.core.Folder;
import com.filenet.api.core.ObjectStore;
import com.filenet.api.meta.ClassDescription;
import com.filenet.api.meta.PropertyDescription;
import com.ibm.ewf.activity.EWFActivityUtil;
import com.ibm.json.java.JSONObject;

abstract public class Template {
	private static final Logger logger = Logger.getLogger(Template.class);
	private String symoblicName = null;
	private String displayName = null;
	private byte[] rawData = null;
	private Object parsedDoc = null;
	private final ReentrantLock parsedDocLocker = new ReentrantLock();
	private LinkedHashMap<String, JSONObject> fieldList = null;
	private boolean isFieldListPrepared = false;
	private String caseType = null;
	private String fileExtName = null;
	private String contentType = null;
	private String renderDijitClass = null;
	
	private static String SYSFIELDNAME_DATE = "SYS.DATE";
	private static String SYSFIELDNAME_TIME = "SYS.TIME";
	
	protected void setContentType(String contentType) {
		this.contentType = contentType;
	}

	protected void setRenderDijitClass(String renderDijitClass) {
		this.renderDijitClass = renderDijitClass;
	}

	protected Template(){;}
	
	public abstract Object parseDoc(byte[] rawTemplateDoc) throws Exception;
	public abstract void fillFields(Object templateDoc, HashMap<String, String> variables, OutputStream oStream) throws Exception;
	public abstract LinkedHashMap<String, JSONObject> listFieldsInDoc(Object templateDoc) throws Exception;
	public abstract void priorToFill(HashMap<String, String> variables) throws Exception;
	
	public String getContentType() {
		return this.contentType;
	}

	public static Template create(Class <? extends Template>adviceTemplateImplClazz, String symbolicName, byte[] rawTemplateDoc) throws IllegalAccessException, InstantiationException {
		Template instance = adviceTemplateImplClazz.newInstance();
		instance.symoblicName = symbolicName;
		instance.rawData = rawTemplateDoc;
		return instance;
	}
	
	public String getRenderDijitClass() {
		return this.renderDijitClass;
	}

	public boolean prepare(boolean guaranteed) {
		boolean lockSuccess = true;
		boolean prepareCarriedOut = false;
		if (guaranteed) {
			parsedDocLocker.lock();
			lockSuccess = true;
		} else {
			lockSuccess = parsedDocLocker.tryLock();
		}
		if (lockSuccess) {
			if (this.parsedDoc != null) {
				parsedDocLocker.unlock();
				return prepareCarriedOut;
			}
			logger.debug("prepare: lock acquired and parsedDoc is null. Parse the doc for the next call.");
			long timeStart = 0;
			long timeElapsed = 0;
			if (logger.isDebugEnabled()) {
				timeStart = System.currentTimeMillis();
			}
			try {
				this.parsedDoc = parseDoc(this.rawData);
				if (!isFieldListPrepared) {
					logger.debug("prepare: prepare the field list.");
					this.fieldList = this.listFieldsInDoc(this.parsedDoc);
					isFieldListPrepared = true;
					this.parsedDoc = parseDoc(this.rawData);
				}
			} catch (Throwable e) {
				logger.error("Error occurred in parsing the template document.", e);
				this.parsedDoc = null;
			} finally {
				parsedDocLocker.unlock();
			}
			prepareCarriedOut = true;
			if (logger.isDebugEnabled()) {
				timeElapsed = System.currentTimeMillis() - timeStart;
				logger.debug("PERF - prepare: time spent for parsing <" + this.getSymbolicName() + "> : " + timeElapsed + " msec.");
			}
		}
		return prepareCarriedOut;
	}
	
	protected Object generateInstance() throws Exception {
		Object ret = null;
		if (parsedDocLocker.tryLock()) {
			if (this.parsedDoc != null) {
				ret = this.parsedDoc;
				this.parsedDoc = null;
			}
			parsedDocLocker.unlock();
		}
		if (ret == null) {
			logger.debug("generateInstance: need to parse the template doc from raw data.");
			long timeStart = 0L;
			if (logger.isDebugEnabled()) {
				timeStart = System.currentTimeMillis();
			}
			ret = parseDoc(this.rawData);
			if (logger.isDebugEnabled()) {
				long timeElapsed = System.currentTimeMillis() - timeStart;
				logger.debug("PERF - generateInstance: time spent for parsing <" + this.getSymbolicName() + "> : " + timeElapsed + " msec."); 
			}
		} else {
			logger.debug("generateInstance: get the last prepared doc directly.");
		}
		return ret;
	}
	
	public void generateAdvice(HashMap<String, String> variables, OutputStream oStream) throws Exception {
		logger.debug("Enter generateAdvice");
		
		priorToFill(variables);
		logger.debug("priorToFill completed.");
		
		addSysFieldToValueMap(variables);
		logger.debug("System fields added.");
		
		Object template = generateInstance();
		long timeStart = 0L;
		if (logger.isDebugEnabled()) {
			timeStart = System.currentTimeMillis();
		}
		logger.debug("Begin to fill variable fields to the template.");
		fillFields(template, variables, oStream);
		logger.debug("Completed filling.");
		if (logger.isDebugEnabled()) {
			long timeForFillVar = System.currentTimeMillis() - timeStart;
			logger.debug("PERF - generateAdvice: time spent for filling variables for template <" + this.getSymbolicName() + "> : " + timeForFillVar + " msec."); 
		}
		logger.debug("Exit generateAdvice");
	}
	
	public byte[] generateAdvice(HashMap<String, String> fields) throws Exception {
		logger.debug("Enter generateAdvice");
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		generateAdvice(fields, bos);
		return bos.toByteArray();
	}
	
	public String getSymbolicName() {
		return this.symoblicName;
	}
	
	private static String getExtName(String fileName) {
		if (fileName == null || fileName.isEmpty() || !fileName.contains(".")) {
			return null;
		}

		String fileExtName = null;
		int pos = fileName.lastIndexOf('.');
		int extNameLen = fileName.length() - pos - 1;
		if (extNameLen > 1 && extNameLen < 10) { 
			fileExtName = fileName.substring(pos + 1).trim().toLowerCase();
		}
		return fileExtName;
	}
	
	public String getDisplayName() {
		String retDisplayName = this.displayName;
		if (retDisplayName == null || retDisplayName.isEmpty()) {
			retDisplayName = this.getSymbolicName();
			if (retDisplayName.contains("/")) {
				retDisplayName = retDisplayName.substring(retDisplayName.indexOf("/") + 1);
			}
			String extName = getExtName(this.getSymbolicName());
			if (extName != null) {
				retDisplayName = retDisplayName.substring(0, retDisplayName.length() - extName.length() - 1);
			}
		}
		return retDisplayName;
	}
	
	public LinkedHashMap<String, JSONObject> listFields() throws Exception {
		this.prepare(true);
		LinkedHashMap<String, JSONObject> ret = new LinkedHashMap<String, JSONObject>();
		Iterator<String> iter = this.fieldList.keySet().iterator();
		while (iter.hasNext()) {
			String key = iter.next();
			JSONObject jsonValue = this.fieldList.get(key);
			ret.put(key, EWFActivityUtil.cloneJson(jsonValue));
		}
		logger.debug("Template.listFields : " + ret.size() + " field(s) listed in template " + this.getSymbolicName());
		return ret;
	}
	
	public LinkedHashMap<String, JSONObject> listFieldsWithValue(ObjectStore os, Folder caseFolder) throws Exception {
		LinkedHashMap<String, JSONObject> fieldsMap = listFields();
		retrieveFieldValues(os, caseFolder, fieldsMap, null);
		return fieldsMap;
	}
	
	protected void retrieveFieldValues(ObjectStore os, Folder caseFolder, LinkedHashMap<String, JSONObject> fieldMap, String reservedStr) throws Exception {
		for (String fieldName : fieldMap.keySet()) {
			String fieldPrefix = getFieldPrefix(fieldName);
			if (EWFActivityUtil.PROP_REF_CASEFOLDER.equals(fieldPrefix)) {
				String caseProp = fieldName.substring(fieldPrefix.length() + 1);
				Object casePropValue = null;
				String propDispName = null;
				if (caseFolder.getProperties().isPropertyPresent(caseProp)) {
					casePropValue = caseFolder.getProperties().getObjectValue(caseProp);
					ClassDescription ceClass = caseFolder.get_ClassDescription();
					Iterator iter = ceClass.get_PropertyDescriptions().iterator();
					while (iter.hasNext()) {
						PropertyDescription propDesc = (PropertyDescription) iter.next();
						String propDescName = propDesc.get_SymbolicName();
						if (!propDescName.equals(caseProp)) {
							continue;
						}
						propDispName = propDesc.get_DisplayName();
						break;
					}
				} else {
					logger.warn("retrieveFieldValues: WARNING: cannot find the corresponding case property for field " 
							+ fieldName + " of template " + this.getSymbolicName());
				}
				JSONObject fieldJson = fieldMap.get(fieldName);
				fieldJson.put(EWFActivityUtil.JSONKEY_VALUE, ceValueToString(casePropValue));
				if (propDispName != null && !propDispName.isEmpty()) {
					fieldJson.put(EWFActivityUtil.JSONKEY_DISPLAY_NAME, propDispName);
				}
			}
		}
	}
	
	private static String ceValueToString(Object ceValue) {
		if (ceValue == null) {
			return null;
		}
		StringBuilder sbValue = new StringBuilder();
		if (ceValue instanceof StringList) {
			Iterator iter = ((StringList)ceValue).iterator();
			while (iter.hasNext()) {
				sbValue.append(iter.next().toString()); 
			}
		} else if(ceValue instanceof IdList) {
			Iterator iter = ((IdList)ceValue).iterator();
			while (iter.hasNext()) {
				sbValue.append(iter.next().toString()); 
			}
		} else if(ceValue instanceof Integer32List) {
			Iterator iter = ((Integer32List)ceValue).iterator();
			while (iter.hasNext()) {
				sbValue.append(iter.next().toString()); 
			}
		} else if(ceValue instanceof Float64List) {
			Iterator iter = ((Float64List)ceValue).iterator();
			while (iter.hasNext()) {
				sbValue.append(iter.next().toString()); 
			}
		} else if(ceValue instanceof DateTimeList) {
			Iterator iter = ((DateTimeList)ceValue).iterator();
			while (iter.hasNext()) {
				sbValue.append(iter.next().toString()); 
			}
		} else {
			sbValue.append(ceValue.toString()); 
		}
		return sbValue.toString();
	}
	
	protected static String getFieldPrefix(String fieldName) {
		if (fieldName == null) {
			return null;
		}
		if (fieldName.contains(".")) {
			return fieldName.substring(0, fieldName.indexOf('.'));
		} else {
			return "";
		}
	}
	
	public String getCaseType() {
		return this.caseType;
	}
	
	protected void setCaseType(String caseType) {
		this.caseType = caseType;
	}

	public String getFileExiName() {
		return fileExtName;
	}
	
	protected void setFileExiName(String _fileExtName) {
		if (_fileExtName != null) {
			_fileExtName = _fileExtName.trim();
			if (!_fileExtName.isEmpty()) {
				this.fileExtName = _fileExtName;
			}
		}
	}
	
	public String getOutputFileName() {
		String fileName = this.getDisplayName();
		String _fileExtName = this.getFileExiName();
		
		if (_fileExtName != null && !_fileExtName.isEmpty()) {
			fileName = fileName + "." + _fileExtName;
		}
		return fileName;
	}
	
	private static void addSysFieldToValueMap(HashMap<String, String> valueMap) {
		java.util.Date curDate = new java.util.Date(System.currentTimeMillis());
		valueMap.put(SYSFIELDNAME_DATE, EWFActivityUtil.dateToSgDateString(curDate, false));
		valueMap.put(SYSFIELDNAME_TIME, EWFActivityUtil.dateToSgTimeString(curDate));
	}
}
