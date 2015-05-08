package fi.hut.cs.drumbeat.ifc.convert.stff2ifc;

/**
 * This class is for parsing IFC schema from an input stream.
 * 
 * The IFC syntax format is based on this doc:
 * http://iaiweb.lbl.gov/Resources/IFC_Releases/IFC_Release_2.0/BETA_Docs_for_Review/IFC_R2_SpecDevGuide_Beta_d2.PDF
 * (page A-27).
 * 
 * This parser includes only minimum syntax checking and ignores many insignificant keywords
 * such as SUPERTYPE OF, DERIVE, WHERE, INVERSE, etc.
 * 
 *  
 * @author Nam Vu Hoang
 * 
 * History:
 * 20120217 - Created
 */

import java.io.*;
import java.util.*;

import fi.hut.cs.drumbeat.common.string.RegexUtils;
import fi.hut.cs.drumbeat.common.string.StringUtils;
import fi.hut.cs.drumbeat.ifc.common.IfcException;
import fi.hut.cs.drumbeat.ifc.common.IfcHelper;
import fi.hut.cs.drumbeat.ifc.common.IfcNotFoundException;
import fi.hut.cs.drumbeat.ifc.common.IfcVocabulary;
import fi.hut.cs.drumbeat.ifc.data.Cardinality;
import fi.hut.cs.drumbeat.ifc.data.schema.*;


/**
 * 
 * Parser of IFC schema from input stream   
 * 
 * @author vuhoan1
 *
 */
public class IfcSchemaParser {
	
	/**
	 * Cache for EXPRESS schema of STFF header section
	 */
	private static IfcSchema stffExpressSchema;
	
	/**
	 * The input reader, reads line by line, wraps original input stream inside
	 */
	private IfcLineReader lineReader;
	
	/**
	 * The output schema
	 */
	private IfcSchema schema;
	
	private List<IfcEntityTypeInfoText> entityTypeInfoTexts = new ArrayList<IfcEntityTypeInfoText>();
	
	/**
	 * Creates a new parser. For internal use.
	 * 
	 * @param input
	 */
	protected IfcSchemaParser(InputStream input) {
		lineReader = new IfcLineReader(input);
	}

	/**
	 * Parses an IFC schema from an input stream. This is the main entry of the parser. 
	 * 
	 * @param input
	 * @return {@link IfcSchema}
	 * @throws IfcParserException
	 */
	public static IfcSchema parse(InputStream input) throws IfcParserException {		
		IfcSchemaParser parser = new IfcSchemaParser(input);		
		return parser.parseSchema();
	}	
	
	/**
	 * Parses schema
	 * 
	 * @return {@link IfcSchema}
	 * @throws IfcParserException
	 */
	private IfcSchema parseSchema() throws IfcParserException {
		
		try {
			String statement = lineReader.getNextStatement();
			String tokens[] = RegexUtils.split2(statement, RegexUtils.WHITE_SPACE);
			
			if (tokens.length != 2 || !tokens[0].equals(IfcVocabulary.ExpressFormat.SCHEMA)) {
				throw new IfcFormatException("Invalid schema");			
			}
			
			//
			// get schema version
			//
			String version = tokens[1].trim();
			
			schema = new IfcSchema(version);
			
			//
			// read all type definitions,
			// parse, create and put new types into the schema
			//
			for (;;) {
				statement = lineReader.getNextStatement();				
				if (statement == null) {
					throw new IfcFormatException(String.format("Expected '%s'", IfcVocabulary.ExpressFormat.END_SCHEMA));
				}
					
				tokens = RegexUtils.split2(statement, RegexUtils.WHITE_SPACE);
				
				if (tokens[0].equals(IfcVocabulary.ExpressFormat.TYPE)) {
					
					// parse an non-entity type info
					IfcDefinedTypeInfo definedTypeInfo = parseDefinedTypeInfo(tokens);
					schema.addDefinedTypeInfo(definedTypeInfo);
					
				} else if (tokens[0].equals(IfcVocabulary.ExpressFormat.ENTITY)) {
					
					// get the entity type body and put into the cache for LATER BINDING
					IfcEntityTypeInfoText entityTypeInfoText =  parseEntityTypeInfoText(tokens);
					entityTypeInfoTexts.add(entityTypeInfoText);						
					schema.addEntityTypeInfo(entityTypeInfoText.getEntityTypeInfo());
					
				} else if (tokens[0].equals(IfcVocabulary.ExpressFormat.END_SCHEMA)) {
					break;
				}
					
			}
			
//				//
//				// bind all types
//				//
//				for (IfcDefinedTypeInfo typeInfo : schema.getDefinedTypeInfos()) {
//					if (typeInfo instanceof IIfcLateBindingTypeInfo) {
//						((IIfcLateBindingTypeInfo)typeInfo).bindTypeInfo(schema);
//					}				
//				}
			
			//
			// bind entity types with their supertypes
			// bind entity types' attributes with the attribute types
			//
			for (IfcEntityTypeInfoText entityTypeInfoText : entityTypeInfoTexts) {
				bindEntitySuperTypeAndAttributes(entityTypeInfoText);
			}
			
			//
			// bind entity types' inverse links and unique keys
			// 
			for (IfcEntityTypeInfoText entityTypeInfoText : entityTypeInfoTexts) {
				bindEntityInverseLinks(entityTypeInfoText);
				bindEntityUniqueKeys(entityTypeInfoText);
			}				

			//
			// return schema
			//
			return schema;
				
		} catch (IfcParserException e) {
			throw e;
		} catch (Exception e) {
			throw new IfcParserException(e);
		} finally {
			entityTypeInfoTexts = null;			
		}
	}
	
	private IfcDefinedTypeInfo parseDefinedTypeInfo(String[] tokens) throws IOException, IfcFormatException, IfcNotFoundException {
		
		tokens = RegexUtils.split2(tokens[1].trim(), RegexUtils.WHITE_SPACE);			
		String typeName = IfcHelper.getFormattedTypeName(tokens[0]);
		
		tokens = RegexUtils.split2(tokens[1], IfcVocabulary.ExpressFormat.EQUAL);			
		String typeInfoString = tokens[1].trim();
		
		IfcDefinedTypeInfo typeInfo = parseDefinedTypeInfoBody(typeInfoString, typeName);
		
		for (;;) {

			String statement = lineReader.getNextStatement();
			
			if (statement != null) {
				
				if (statement.equals(IfcVocabulary.ExpressFormat.END_TYPE)) {

					return typeInfo;
				}
				
			} else {
				throw new IfcFormatException(String.format("Expected '%s'", IfcVocabulary.ExpressFormat.END_TYPE));
			}
		}		
	}
	
	/**
	 * Parses a type string to get a type
	 * @param typeInfoString The type info string to parse
	 * @param typeName The name of external type
	 * @return
	 * @throws IOException
	 * @throws IfcFormatException
	 */
	private IfcDefinedTypeInfo parseDefinedTypeInfoBody(String typeInfoString, String typeName) throws IOException, IfcFormatException {		
		String[] tokens = RegexUtils.split2(typeInfoString, RegexUtils.WHITE_SPACE);
		
		IfcCollectionKindEnum collectionKind = IfcCollectionKindEnum.parse(tokens[0]);
		
		if (collectionKind != null) {
			//
			// create a collection type
			//
			boolean isSorted = !tokens[0].equals(IfcVocabulary.ExpressFormat.SET);
			
			tokens = RegexUtils.split2(tokens[1], IfcVocabulary.ExpressFormat.OF);
			
			Cardinality cardinality = parseCardinality(tokens[0]);
			
			tokens = RegexUtils.split2(tokens[1], IfcVocabulary.ExpressFormat.UNIQUE);			
			boolean itemsAreUnique = tokens.length == 2;
			
			typeInfoString = itemsAreUnique ? tokens[1].trim() : tokens[0].trim();
			
			String itemTypeInfoName = IfcHelper.getFormattedTypeName(typeInfoString);
			
			tokens = RegexUtils.split2(itemTypeInfoName, RegexUtils.WHITE_SPACE);
			
			if (isCollectionTypeHeader(tokens[0])) {
				parseDefinedTypeInfoBody(typeInfoString, itemTypeInfoName); 
			}		
			
			IfcCollectionTypeInfo typeInfo = new IfcCollectionTypeInfo(schema, typeName, collectionKind, itemTypeInfoName);
			typeInfo.setCardinality(cardinality);
			return typeInfo;		
			
		} else if (tokens[0].equals(IfcVocabulary.ExpressFormat.SELECT)) {
			
			//
			// create a select type
			//
			tokens = StringUtils.getStringBetweenBrackets(tokens[1].trim());
			tokens = RegexUtils.splitAll(tokens[0], StringUtils.COMMA);
			List<String> selectTypeInfoNames = new ArrayList<String>();
			for (int i = 0; i < tokens.length; ++i) {
				selectTypeInfoNames.add(IfcHelper.getFormattedTypeName(tokens[i].trim()));
			}
			
			IfcSelectTypeInfo typeInfo = new IfcSelectTypeInfo(schema, typeName, selectTypeInfoNames);			
			
			return typeInfo;
			
		} else if (tokens[0].equals(IfcVocabulary.ExpressFormat.ENUMERATION)) {

			//
			// create a enumeration type
			//
			tokens = RegexUtils.split2(tokens[1], IfcVocabulary.ExpressFormat.OF);
			tokens = StringUtils.getStringBetweenBrackets(tokens[1].trim());
			tokens = RegexUtils.splitAll(tokens[0], StringUtils.COMMA);
			
			List<String> values = new ArrayList<String>();
			for (String value : tokens) {
				values.add(value.trim());
			}
			
			IfcEnumerationTypeInfo typeInfo = new IfcEnumerationTypeInfo(schema, typeName, values);
			return typeInfo;
			
		} else {			
			
			String internalTypeInfoName = IfcHelper.getFormattedTypeName(tokens[0]);
			
			
			if (typeName != null) {
			
				//
				// create a redirect types
				//
				if (typeName.equalsIgnoreCase(IfcVocabulary.TypeNames.IFC_TIME_STAMP)) {
					internalTypeInfoName = IfcVocabulary.TypeNames.DATETIME;
				}
				return new IfcRedirectTypeInfo(schema, typeName, internalTypeInfoName);
			} else {
				
				throw new NullPointerException("typeName");
				
			}
				
		}			
			
	}
	
	private static Cardinality parseCardinality(String s) {
		
		s = s.trim();
		
		Cardinality cardinality = new Cardinality(Cardinality.ONE, Cardinality.ONE);

		if (!s.isEmpty()) {
		
			String[] cardinalityTokens = 
					RegexUtils.split2(s.replaceAll("[\\[\\]\\s]", ""), StringUtils.COLON);			
			
			if (!cardinalityTokens[0].equals(IfcVocabulary.ExpressFormat.UNBOUNDED)) {
				cardinality.setMin(Integer.parseInt(cardinalityTokens[0]));
			} else {
				cardinality.setMin(Cardinality.UNBOUNDED);				
			}
			
			if (!cardinalityTokens[1].equals(IfcVocabulary.ExpressFormat.UNBOUNDED)) {
				cardinality.setMax(Integer.parseInt(cardinalityTokens[1]));
			} else {
				cardinality.setMax(Cardinality.UNBOUNDED);				
			}
			
		}
		
		return cardinality;		
	}

	/**
	 * Subsections of section ENTITY
	 *
	 */
	public enum EntityFormatSection {
		ATTRIBUTES,
		DERIVE,
		INVERSE,
		UNIQUE,
		WHERE,		
	}
	
	/**
	 * Parses tokens to get an entity info
	 * @param tokens
	 * @return
	 * @throws IfcFormatException
	 * @throws IOException
	 * @throws IfcNotFoundException 
	 */
	private IfcEntityTypeInfoText parseEntityTypeInfoText(String[] tokens) throws IfcFormatException, IOException {
		
		if (tokens.length != 2) {
			throw new IfcFormatException("Invalid format");		
		}
			
		// get entity type name
		tokens = RegexUtils.split2(tokens[1].trim(), RegexUtils.WHITE_SPACE);		
		
		String entityTypeName = tokens[0];
		IfcEntityTypeInfo entityTypeInfo;
		try {
			entityTypeInfo = schema.getEntityTypeInfo(entityTypeName);
		} catch (IfcNotFoundException e) {
			entityTypeInfo = new IfcEntityTypeInfo(schema, entityTypeName);
		}
		IfcEntityTypeInfoText entityTypeInfoText = new IfcEntityTypeInfoText(entityTypeInfo);			
		
		if (tokens.length == 2) {
			
			// 
			// check if it's an abstract class
			//
			tokens = RegexUtils.split2(tokens[1], IfcVocabulary.ExpressFormat.ABSTRACT);				
			int tokenIndex;				
			if (tokens.length == 2) {
				entityTypeInfo.setAbstractSuperType(true);
				tokenIndex = 1;
			} else {
				tokenIndex = 0;
			}
			
			// 
			// get super entity type name
			//
			tokens = RegexUtils.split2(tokens[tokenIndex], IfcVocabulary.ExpressFormat.SUBTYPE);
			
			if (tokens.length == 2) {
				tokens = RegexUtils.split2(tokens[1], IfcVocabulary.ExpressFormat.OF);				
				tokens = StringUtils.getStringBetweenBrackets(tokens[1].trim());
				
				String superTypeName = IfcHelper.getFormattedTypeName(tokens[0].trim());
				entityTypeInfoText.setSuperTypeName(superTypeName);
			}
		}			
		
		EntityFormatSection currentSection = EntityFormatSection.ATTRIBUTES;
		List<String> listOfStatements = entityTypeInfoText.getAttributeStatements();
		
		for (;;) {
			
			String statement = lineReader.getNextStatement();				
			
			if (statement == null) {
				throw new IfcFormatException(String.format("Expected '%s'", IfcVocabulary.ExpressFormat.END_ENTITY));
			}
				
			tokens = RegexUtils.split2(statement, RegexUtils.WHITE_SPACE);
			
			if (tokens[0].equals(IfcVocabulary.ExpressFormat.END_ENTITY)) {						
				return entityTypeInfoText;
			} else if (currentSection.compareTo(EntityFormatSection.WHERE) <= 0 && tokens[0].equals(IfcVocabulary.ExpressFormat.WHERE)) {
				currentSection = EntityFormatSection.WHERE;
				listOfStatements = null;
			} else if (currentSection.compareTo(EntityFormatSection.UNIQUE) <= 0 && tokens[0].equals(IfcVocabulary.ExpressFormat.UNIQUE)) {
				currentSection = EntityFormatSection.UNIQUE;						
				listOfStatements = entityTypeInfoText.getUniqueKeysStatements();
			} else if (currentSection.compareTo(EntityFormatSection.INVERSE) <= 0 && tokens[0].equals(IfcVocabulary.ExpressFormat.INVERSE)) {
				currentSection = EntityFormatSection.INVERSE;						
				statement = tokens[1].trim();
				listOfStatements = entityTypeInfoText.getInverseLinkStatements();
			} else if (currentSection.compareTo(EntityFormatSection.DERIVE) <= 0 && tokens[0].equals(IfcVocabulary.ExpressFormat.DERIVE)) {
				currentSection = EntityFormatSection.DERIVE;						
				listOfStatements = null;
			}
			
			if (listOfStatements != null) {
				listOfStatements.add(statement);						
			}
				
		} // for
	}
	
	private IfcCollectionTypeInfo parseCollectionType(IfcCollectionKindEnum collectionKind, String typeInfoString) throws IfcFormatException {
		
		// read collection cardinality
		String[] tokens = RegexUtils.split2(typeInfoString, IfcVocabulary.ExpressFormat.OF);				
		Cardinality collectionCardinality = parseCardinality(tokens[0]);				
		tokens = RegexUtils.split2(tokens[1], IfcVocabulary.ExpressFormat.UNIQUE);				
		
		boolean collectionItemsAreUnique = tokens.length == 2;		
		typeInfoString = collectionItemsAreUnique ? tokens[1].trim(): tokens[0].trim();		
		
		tokens = RegexUtils.split2(typeInfoString, RegexUtils.WHITE_SPACE);
		
		if (tokens.length == 1) {
			
			//
			// create or get the collection type
			//
			String collectionItemTypeInfoName = IfcHelper.getFormattedTypeName(typeInfoString);				
			String collectionTypeInfoName = IfcCollectionTypeInfo.formatCollectionTypeName(collectionKind, collectionItemTypeInfoName, collectionCardinality);
			try {
				return (IfcCollectionTypeInfo)schema.getTypeInfo(collectionTypeInfoName);
			} catch (IfcNotFoundException e) {					
			
				// create or get the same collection type, but without cardinality
				IfcCollectionTypeInfo superCollectionTypeInfoWithoutCardinalities;
				String superCollectionTypeInfoName = IfcCollectionTypeInfo.formatCollectionTypeName(collectionKind, collectionItemTypeInfoName, null);
				assert (superCollectionTypeInfoName != collectionTypeInfoName) : "Expected: (superCollectionTypeInfoName != collectionTypeInfoName)";
				try {
					superCollectionTypeInfoWithoutCardinalities = (IfcCollectionTypeInfo)schema.getTypeInfo(superCollectionTypeInfoName);
				} catch (IfcNotFoundException e1) {					
					// create collection type (with cardinality)
					superCollectionTypeInfoWithoutCardinalities = new IfcCollectionTypeInfo(schema, superCollectionTypeInfoName, collectionKind, collectionItemTypeInfoName);
					superCollectionTypeInfoWithoutCardinalities.setCardinality(null);
					//superCollectionTypeInfo.bindTypeInfo(schema);
					schema.addDefinedTypeInfo(superCollectionTypeInfoWithoutCardinalities);
				}
				
				// create collection type (with cardinality)
				IfcCollectionTypeInfo collectionTypeInfo = new IfcCollectionTypeInfo(schema, collectionTypeInfoName, collectionKind, collectionItemTypeInfoName);
				collectionTypeInfo.setCardinality(collectionCardinality);
				collectionTypeInfo.setSuperCollectionTypeWithoutCardinalities(superCollectionTypeInfoWithoutCardinalities);
				//collectionTypeInfo.bindTypeInfo(schema);
				schema.addDefinedTypeInfo(collectionTypeInfo);
				
				return collectionTypeInfo;
			}
			
		} else {
			
			IfcCollectionKindEnum collectionKind2 = IfcCollectionKindEnum.parse(tokens[0]);			
			if (collectionKind == null) {
				throw new IfcFormatException(String.format("Expected one of %s", IfcCollectionKindEnum.values().toString()));				
			}
			
			// case SET/LIST/ARRAY/BAG OF LIST OF ... 

			IfcCollectionTypeInfo collectionItemTypeInfo = parseCollectionType(collectionKind2, tokens[1]);
			
			// create or get the super collection type (without cardinality)
			IfcCollectionTypeInfo superCollectionTypeInfoWithoutCardinalities;
			String collectionTypeInfoName = collectionItemTypeInfo.getName();
			String superCollectionTypeInfoName = IfcCollectionTypeInfo.formatCollectionTypeName(collectionKind2, collectionTypeInfoName, null);
			assert (superCollectionTypeInfoName != collectionTypeInfoName) : "Expected: (superCollectionTypeInfoName != collectionTypeInfoName)";
			try {
				superCollectionTypeInfoWithoutCardinalities = (IfcCollectionTypeInfo)schema.getTypeInfo(superCollectionTypeInfoName);
			} catch (IfcNotFoundException e1) {					
				// create collection type (with cardinality)
				superCollectionTypeInfoWithoutCardinalities = new IfcCollectionTypeInfo(schema, superCollectionTypeInfoName, collectionKind2, collectionItemTypeInfo);
				superCollectionTypeInfoWithoutCardinalities.setCardinality(null);
				//superCollectionTypeInfo.bindTypeInfo(schema);
				schema.addDefinedTypeInfo(superCollectionTypeInfoWithoutCardinalities);
			}
			
			// create collection type (with cardinality)
			IfcCollectionTypeInfo collectionTypeInfo = new IfcCollectionTypeInfo(schema, collectionTypeInfoName, collectionKind2, collectionItemTypeInfo);
			collectionTypeInfo.setCardinality(collectionCardinality);
			collectionTypeInfo.setSuperCollectionTypeWithoutCardinalities(superCollectionTypeInfoWithoutCardinalities);
			//collectionTypeInfo.bindTypeInfo(schema);
			schema.addDefinedTypeInfo(collectionTypeInfo);
			
			return collectionTypeInfo;
			
		}
			
	}
	
	private void bindEntitySuperTypeAndAttributes(IfcEntityTypeInfoText entityTypeInfoText) throws IfcFormatException, IfcNotFoundException, IOException {		
				
		IfcEntityTypeInfo entityTypeInfo = entityTypeInfoText.getEntityTypeInfo();
		
		String superTypeInfoName = entityTypeInfoText.getSuperTypeName();
		if (superTypeInfoName != null) {
			IfcEntityTypeInfo superTypeInfo = schema.getEntityTypeInfo(superTypeInfoName); 
			entityTypeInfo.setSuperTypeInfo(superTypeInfo);
		}

		for (String statement : entityTypeInfoText.getAttributeStatements()) {
			
			String[] tokens = RegexUtils.split2(statement, StringUtils.COLON);						
			String attributeName = IfcHelper.getFormattedAttributeName(tokens[0].trim());

			tokens = RegexUtils.split2(tokens[1].trim(), IfcVocabulary.ExpressFormat.OPTIONAL);						
			boolean isOptional;
			if (tokens.length == 1) {
				isOptional = false;
				tokens = RegexUtils.split2(tokens[0].trim(), RegexUtils.WHITE_SPACE);
			} else {
				isOptional = true;
				tokens = RegexUtils.split2(tokens[1].trim(), RegexUtils.WHITE_SPACE);
			}
			
//			Cardinality cardinality;
			IfcTypeInfo attributeTypeInfo;
			
			IfcCollectionKindEnum collectionKind = IfcCollectionKindEnum.parse(tokens[0]); 
			
			if (collectionKind != null) {
				
				attributeTypeInfo = parseCollectionType(collectionKind, tokens[1]);
//				cardinality = new Cardinality(isOptional ? Cardinality.ZERO : Cardinality.ONE, Cardinality.ONE);
				
//			} else if (tokens[0].equals(IfcVocabulary.ExpressFormat.SET)) {
//				
//				tokens = RegexUtils.split2(tokens[1], IfcVocabulary.ExpressFormat.OF);				
//				cardinality = parseCardinality(tokens[0]);
//				assert (!isOptional || cardinality.getMin() == Cardinality.ONE) :
//					String.format("Expected: !isOptional || cardinality.getMin() == Cardinality.ONE (Actual: cardinality.getMin() = %d, type = %s)",
//							cardinality.getMin(), entityTypeInfo.getName());
//				tokens = RegexUtils.split2(tokens[1], IfcVocabulary.ExpressFormat.UNIQUE);				
//				boolean listItemsAreUnique = tokens.length == 2;				
//				String attributeTypeInfoName = IfcHelper.getFormattedTypeName(listItemsAreUnique ? tokens[1].trim(): tokens[0].trim());
//				attributeTypeInfo = schema.getTypeInfo(attributeTypeInfoName);
				
			} else {
//				cardinality = new Cardinality(isOptional ? Cardinality.ZERO : Cardinality.ONE, Cardinality.ONE);
				String attributeTypeInfoName = IfcHelper.getFormattedTypeName(tokens[0]);
				attributeTypeInfo = schema.getTypeInfo(attributeTypeInfoName);
			}
			
			IfcAttributeInfo attributeInfo;
			if (attributeTypeInfo.isEntityOrSelectType()) {
				attributeInfo = new IfcLinkInfo(entityTypeInfo, attributeName, attributeTypeInfo);
			} else {
				attributeInfo = new IfcAttributeInfo(entityTypeInfo, attributeName, attributeTypeInfo);				
			}
			attributeInfo.setOptional(isOptional);
//			attributeInfo.setCardinality(cardinality);
//			if (cardinality.isSingle()) {
//				attributeInfo.setFunctional(true);
//			}
			
			entityTypeInfo.addAttributeInfo(attributeInfo);			
		}			

	}
	
	public static boolean isCollectionTypeHeader(String typeHeader) {
		return typeHeader.equals(IfcVocabulary.ExpressFormat.SET) ||
				typeHeader.equals(IfcVocabulary.ExpressFormat.LIST) ||
				typeHeader.equals(IfcVocabulary.ExpressFormat.ARRAY) ||
				typeHeader.equals(IfcVocabulary.ExpressFormat.BAG);
	}
	
	
	
	private void bindEntityInverseLinks(IfcEntityTypeInfoText entityTypeInfoText) throws IfcException {

		IfcEntityTypeInfo entityTypeInfo = entityTypeInfoText.getEntityTypeInfo();
		
		for (String statement : entityTypeInfoText.getInverseLinkStatements()) {
			
			String[] tokens = RegexUtils.split2(statement, StringUtils.COLON);						
			String attributeName = IfcHelper.getFormattedAttributeName(tokens[0].trim());

			tokens = RegexUtils.split2(tokens[1].trim(), IfcVocabulary.ExpressFormat.SET);
			
			Cardinality cardinality;  
			
			if (tokens.length == 1) {
								
				cardinality = new Cardinality(Cardinality.ONE, Cardinality.ONE);
				tokens = RegexUtils.split2(tokens[0].trim(), RegexUtils.WHITE_SPACE);
				
			} else {
				
				tokens = RegexUtils.split2(tokens[1].trim(), IfcVocabulary.ExpressFormat.OF);
				cardinality = parseCardinality(tokens[0]);
				tokens = RegexUtils.split2(tokens[1].trim(), RegexUtils.WHITE_SPACE);
				
			}
			
			String sourceEntityTypeInfoName = tokens[0];

			tokens = RegexUtils.split2(tokens[1].trim(), IfcVocabulary.ExpressFormat.FOR);
			String outgoingLinkName = IfcHelper.getFormattedAttributeName(tokens[1].trim());
			
			IfcEntityTypeInfo sourceEntityTypeInfo = schema.getEntityTypeInfo(sourceEntityTypeInfoName);
			IfcLinkInfo outgoingLinkInfo = (IfcLinkInfo)sourceEntityTypeInfo.getAttributeInfo(outgoingLinkName);
			
			IfcInverseLinkInfo inverseLinkInfo =
					new IfcInverseLinkInfo(entityTypeInfo, attributeName, sourceEntityTypeInfo, outgoingLinkInfo);
			inverseLinkInfo.setCardinality(cardinality);
			if (cardinality.isSingle()) {
				inverseLinkInfo.setFunctional(true);
				outgoingLinkInfo.setInverseFunctional(true);
			}
			inverseLinkInfo.setInverseFunctional(outgoingLinkInfo.isFunctional());
			entityTypeInfo.addInverseLinkInfo(inverseLinkInfo);
		}
	}
	
	private void bindEntityUniqueKeys(IfcEntityTypeInfoText entityTypeInfoText) throws IfcException {

		IfcEntityTypeInfo entityTypeInfo = entityTypeInfoText.getEntityTypeInfo();
		
		for (String statement : entityTypeInfoText.getUniqueKeysStatements()) {
			
			String[] tokens = RegexUtils.split2(statement, StringUtils.COLON);						
//			String uniqueKeyName = tokens[0].trim();
			
			IfcUniqueKeyInfo uniqueKeyInfo = new IfcUniqueKeyInfo();

			while (tokens.length > 1) {
				tokens = RegexUtils.split2(tokens[1].trim(), RegexUtils.COMMA);
				String attributeName = IfcHelper.getFormattedAttributeName(tokens[0].trim());
				IfcAttributeInfo attributeInfo = entityTypeInfo.getAttributeInfo(attributeName); 
				uniqueKeyInfo.addAttributeInfo(attributeInfo);
			}
			
			entityTypeInfo.addUniqueKey(uniqueKeyInfo);
			
			if (uniqueKeyInfo.size() == 1) {
				uniqueKeyInfo.getFirstAttributeInfo().setInverseFunctional(true); 
			}
		}
	}
	
	public static IfcSchema getStepSchema() throws IfcParserException {
		if (stffExpressSchema == null) {
			stffExpressSchema = parse(new ByteArrayInputStream(IfcVocabulary.StepFormat.Header.SCHEMA_STRING.getBytes()));
		}
		return stffExpressSchema;
	}
	
}
