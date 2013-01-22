/**
 * Copyright (C) 2012 pm286 <peter.murray.rust@googlemail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.xmlcml.pdf2svg;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.encoding.Encoding;
import org.apache.pdfbox.pdfviewer.PageDrawer;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMatrix;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.PDLineDashPattern;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.text.PDTextState;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.TextPosition;
import org.apache.xerces.impl.dv.util.Base64;
import org.xmlcml.euclid.Angle;
import org.xmlcml.euclid.Real2;
import org.xmlcml.euclid.Real2Range;
import org.xmlcml.euclid.RealArray;
import org.xmlcml.euclid.RealMatrix;
import org.xmlcml.euclid.Transform2;
import org.xmlcml.graphics.svg.SVGClipPath;
import org.xmlcml.graphics.svg.SVGDefs;
import org.xmlcml.graphics.svg.SVGElement;
import org.xmlcml.graphics.svg.SVGImage;
import org.xmlcml.graphics.svg.SVGPath;
import org.xmlcml.graphics.svg.SVGRect;
import org.xmlcml.graphics.svg.SVGSVG;
import org.xmlcml.graphics.svg.SVGText;
import org.xmlcml.graphics.svg.SVGTitle;
import org.xmlcml.graphics.svg.SVGUtil;
import org.xmlcml.pdf2svg.util.PDF2SVGUtil;

/** converts a PDPage to SVG
 * Originally used PageDrawer to capture the PDF operations.These have been
 * largely intercepted and maybe PageDrawer could be retired at some stage
 * @author pm286 and Murray Jensen
 *
 */
public class PDFPage2SVGConverter extends PageDrawer {
	
	private static final String CLIP_PATH = "clipPath";

	private final static Logger LOG = Logger.getLogger(PDFPage2SVGConverter.class);

	// only use if mediaBox fails to give dimension
	private static final Dimension DEFAULT_DIMENSION = new Dimension(800, 800);
	private static final int BADCHAR = (char)0X2775;
	static {
		LOG.setLevel(Level.DEBUG);
	}

//	private static double eps = 0.001;

	private BasicStroke basicStroke;
	private SVGSVG svg;
//	private Composite composite;
//	private Paint paint;
	private PDGraphicsState graphicsState;
	private Matrix textPos;
	private PDFont pdFont;

	private String fontFamilyName;
	private String fontName;
//	private Double currentFontSize;
//	private String currentFontStyle;
//	private String currentFontWeight;
	
	private int nPlaces = 3;
	private PDLineDashPattern dashPattern;
	private Double lineWidth;
	private Set<String> clipStringSet;
	private String clipString;
	private PDF2SVGConverter pdf2svgConverter;
	private Encoding encoding; // to distinguish from content-type encoding
	private String charname;
	private Real2 currentXY;
	private String fontSubType;
	private String textContent;
	private AMIFontManager amiFontManager;
	private boolean charWasLogged = false;

//	private FontFamily newFontFamily;

	private AMIFont amiFont;
	private String lastFontName;
	private FontFamily fontFamily;

	private HashMap<String, Integer> integerByClipStringMap;

	private SVGElement defs1;

	private boolean reportedEncodingError = false;;
	

	public PDFPage2SVGConverter() throws IOException {
		super();
	}

	/** called for each page by PDF2SVGConverter
	 * 
	 * @param page
	 * @param converter
	 */
	public SVGSVG convertPageToSVG(PDPage page, PDF2SVGConverter converter) {
		pageSize = null;	// reset size for each page
		this.pdf2svgConverter = converter;
		this.amiFontManager = converter.getAmiFontManager();
		amiFontManager.setNullFontDescriptorReport(true);
		createSVGSVG();
		drawPage(page);
		return svg;
	}
	
	void drawPage(PDPage p) {
		ensurePageSize();
		page = p;
		reportedEncodingError = false;

		try {
			if (page.getContents() != null) {
				PDResources resources = page.findResources();
				LOG.trace("pageSize: "+pageSize);
				ensurePageSize();
				processStream(page, resources, page.getContents().getStream());
			}
		} catch (Exception e) {
			// PDFBox routines have a very bad feature of trapping exceptions
			// this is the best we can do to alert you at this stage
			e.printStackTrace();
			LOG.error("***FAILED " + e);
			throw new RuntimeException("drawPage", e);
		}
		createDefsForClipPaths();
		if (pdf2svgConverter.drawBoxesForClipPaths) {
			drawBoxesForClipPaths();
		}
	}

	private void drawBoxesForClipPaths() {
		ensureClipStringSet();
		String[] color = {"yellow", "blue", "red", "green", "magenta", "cyan"};
		LOG.trace("Clip paths: "+clipStringSet.size());
		int icol = 0;
		for (String shapeString : clipStringSet) {
			LOG.trace("Shape: "+shapeString);
			if (shapeString != null && shapeString.trim().length() > 0) {
				SVGPath path = new SVGPath(shapeString);
				Real2Range bbox = path.getBoundingBox();
				SVGRect box = null;
				box = new SVGRect(bbox);
				box.setFill("none");
				box.setStroke(color[icol]);
				box.setOpacity(1.0);
				box.setStrokeWidth(2.0);
				svg.appendChild(box);
				icol = (icol+1) % 6;
			}
		}
	}

	private void createDefsForClipPaths() {
//   <clipPath clipPathUnits="userSpaceOnUse" id="clipPath14">
//	    <path stroke="black" stroke-width="0.5" fill="none" d="M0 0 L89.814 0 L89.814 113.7113 L0 113.7113 L0 0 Z"/>
//	  </clipPath>
		ensureIntegerByClipStringMap();
		ensureDefs1();
		for (String pathString : integerByClipStringMap.keySet()) {
			Integer serial = integerByClipStringMap.get(pathString);
			SVGClipPath clipPath = new SVGClipPath();
			clipPath.setId(CLIP_PATH+serial);
			defs1.appendChild(clipPath);
			SVGPath path = new SVGPath();
			path.setDString(pathString);
			clipPath.appendChild(path);
		}
	}

    /**
     * DUPLICATE OF SUPER SO WE CAN DEBUG
     * This will draw the page to the requested context.
     *
     * @param g The graphics context to draw onto.
     * @param p The page to draw.
     * @param pageDimension The size of the page to draw.
     *
     * @throws IOException If there is an IO error while drawing the page.
     */
    public void drawPage( Graphics g, PDPage p, Dimension pageDimension ) throws IOException {
    	super.drawPage(g, p, pageDimension);
    	// cannot use this because private
    	// graphics = (Graphics2D)g;
        Graphics2D g2d = (Graphics2D)g;
//	        g2d = (Graphics2D)g;
        page = p;
        pageSize = pageDimension;
        g2d.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
        g2d.setRenderingHint( RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON );
        // Only if there is some content, we have to process it. 
        // Otherwise we are done here and we will produce an empty page
        if ( page.getContents() != null) {
            PDResources resources = page.findResources();
            processStream( page, resources, page.getContents().getStream() );
        }
        List annotations = page.getAnnotations();
        if (annotations.size() > 0) {
        	throw new RuntimeException("ANNOTATIONS");
        }
        for( int i=0; i<annotations.size(); i++ ) {
            PDAnnotation annot = (PDAnnotation)annotations.get( i );
            PDRectangle rect = annot.getRectangle();
            String appearanceName = annot.getAppearanceStream();
            PDAppearanceDictionary appearDictionary = annot.getAppearance();
            if( appearDictionary != null ) {
                if( appearanceName == null ) {
                    appearanceName = "default";
                }
                Map appearanceMap = appearDictionary.getNormalAppearance();
                if (appearanceMap != null) { 
                    PDAppearanceStream appearance = 
                        (PDAppearanceStream)appearanceMap.get( appearanceName ); 
                    if( appearance != null ) { 
                        g.translate( (int)rect.getLowerLeftX(), (int)-rect.getLowerLeftY() ); 
                        processSubStream( page, appearance.getResources(), appearance.getStream() ); 
                        g.translate( (int)-rect.getLowerLeftX(), (int)+rect.getLowerLeftY() ); 
                    }
                }
            }
        }
    }


	private void ensureDefs1() {
/*
<svg fill-opacity="1" 
xmlns="http://www.w3.org/2000/svg">
  <defs id="defs1">
   <clipPath clipPathUnits="userSpaceOnUse" id="clipPath1">
    <path stroke="black" stroke-width="0.5" fill="none" d="M0 0 L595 0 L595 793 L0 793 L0 0 Z"/>
   </clipPath>
   </defs>
 */
		List<SVGElement> defList = SVGUtil.getQuerySVGElements(svg, "/svg:g/svg:defs[@id='defs1']");
		defs1 = (defList.size() > 0) ? defList.get(0) : null;
		if (defs1 == null) {
			defs1 = new SVGDefs();
			defs1.setId("defs1");
			svg.insertChild(defs1, 0);
		}
	}

	/** adds a default pagesize if not given
	 * 
	 */
	private void ensurePageSize() {
		if (pageSize == null) {
			if (page != null) {
				PDRectangle mediaBox = page.findMediaBox();
				pageSize = mediaBox == null ? null : mediaBox.createDimension();
				pageSize = pageSize == null ? DEFAULT_DIMENSION : pageSize;
				LOG.trace("set dimension: "+pageSize);
			}
		}
	}
	

	@Override
	protected void processTextPosition(TextPosition textPosition) {

		charname = null;
		charWasLogged = false;

		pdFont = textPosition.getFont();
		amiFont = amiFontManager.getAmiFontByFont(pdFont);

		setAndProcessFontNameAndFamilyName();

		int charCode = getCharCodeAndSetEncodingAndCharname(textPosition);

		SVGText svgText = new SVGText();
		
		createGraphicsStateAndPaintAndComposite(svgText);
		getAndFormatClipPath();

		if (pdf2svgConverter.useXMLLogger) {
			pdf2svgConverter.xmlLogger.newFont(amiFont);
			if (pdf2svgConverter.xmlLoggerLogGlyphs) {
				captureAndIndexGlyphVector(textPosition, charCode);
			}
		}

		createAndReOrientateTextPosition(textPosition, svgText);

		svgText.setFontWeight(amiFont.getFontWeight());

		if (amiFont.isSymbol() || amiFont.getDictionaryEncoding() != null ||
				(fontFamily != null && fontFamily.getCodePointSet() != null)) {
			//convertNonUnicodeCharacterEncodings();
			//annotateContent(svgText, textContent, charCode, charname, charCode, encoding);
		}

		LOG.trace("Fn: "+fontName+"; Ff: "+fontFamilyName+"; "+textContent+"; "+charCode+"; "+charname);

		float width = getCharacterWidth(pdFont, textContent);
		addContentAndAttributesToSVGText(textPosition, svgText, width, charCode);

		svg.appendChild(svgText);
	}

	private void setAndProcessFontNameAndFamilyName() {
		fontName = amiFont.getFontName();
		if (fontName == null) {
//			throw new RuntimeException("Null font name: "+amiFont);
			LOG.error("Null font name: "+amiFont);
			return;
		} else if (!fontName.equals(lastFontName)) {
			LOG.trace("font from "+lastFontName+" -> "+fontName);
			lastFontName = fontName;
		}
		fontFamilyName = amiFont.getFontFamilyName();
		fontFamily = amiFontManager.getFontFamily(fontFamilyName);
//		if (fontFamily.getCodePointSet() == null && amiFont.isSymbol()) {
//			throw new RuntimeException("Symbol font ("+fontFamilyName+") needs codePointSet");
//		}
//		checkPublisherFontFamily();
	}

//	private void checkPublisherFontFamily() {
//		Publisher publisher = pdf2svgConverter.getPublisher();
//		if (publisher != null) {
//			if (!publisher.containsFontFamilyName(fontFamilyName))  {
//				publisher.addFontFamilyName(fontFamilyName);
//				LOG.trace("added fontFamilyName "+fontFamilyName);
//			} else {
//				LOG.trace("already in publisher fontFamilySet "+fontFamilyName);
//			}
//		}
//	}

	private int getCharCodeAndSetEncodingAndCharname(TextPosition textPosition) {

		encoding = amiFont.getEncoding();
		int[] codePoints = textPosition.getCodePoints();
		LOG.trace("codePoints: "+(codePoints == null ? null : codePoints.length));
		int charCode = -1;
		if (encoding == null) {
			if (codePoints != null) {
				charCode = codePoints[0];
				LOG.trace("charCode "+charCode);
				textContent = ""+(char) charCode;
			}
		} else {
			textContent = textPosition.getCharacter();
			if (textContent.length() > 1) {
				// this can happen for ligatures
				LOG.trace("multi-char string: "+textPosition.getCharacter());
			} 
			charCode = textContent.charAt(0);
		}

		if (encoding == null) {
			if (!reportedEncodingError ) {
				LOG.debug("Null encoding for character: "+charCode+" at "+currentXY+" font: "+fontName+" / "+
			       fontFamilyName+" / "+amiFont.getBaseFont()+
			       "\n                FURTHER ERRORS HIDDEN");
				reportedEncodingError = true;
			}
		} else {
//			if (encoding instanceof DictionaryEncoding) {
				getCharnameThroughEncoding(charCode);
//			}
		}

		return charCode;
	}

	private void getCharnameThroughEncoding(int charCode) {
		try {
			// NOTE: charname is the formal name for the character such as "period", "bracket" or "a", "two"
			charname = encoding.getName(charCode);
			LOG.trace("code "+charCode+" (font: "+fontSubType+" "+fontName+") "+charname);
		} catch (IOException e1) {
			LOG.warn("cannot get char encoding "+" at "+currentXY, e1);
		}
	}

	private void addContentAndAttributesToSVGText(TextPosition textPosition, SVGText svgText,
			float width, int charCode) {
		try {
			svgText.setText(textPosition.getCharacter());
		} catch (RuntimeException e) {
			// drops here if cannot encode as XML character
			annotateUnusualCharacters(textPosition, svgText);
		}
		
		getFontSizeAndSetNotZeroRotations(svgText);
		addAttributesToSVGText(width, svgText);
		addTooltips(svgText, charCode);
		if (amiFont.isItalic() != null && amiFont.isItalic()) {
			svgText.setFontStyle("italic");
		}
		if (amiFont.isBold() != null && amiFont.isBold()) {
			svgText.setFontWeight("bold");
		}
		addCodePointToHighPoints(textPosition);
		if ("Symbol".equals(svgText.getFontFamily())) {
			svgText.setFontFamily("Symbol-X"); // to stop browsers misbehaving
		}

	}

	private void convertNonUnicodeCharacterEncodings() {
		CodePointSet codePointSet = fontFamily.getCodePointSet();
		if (codePointSet != null) {
			CodePoint codePoint = null;
			if (charname != null) {	
				codePoint = codePointSet.getByName(charname);
			} else {
				codePoint = codePointSet.getByDecimal((int)textContent.charAt(0));
			}
			if (codePoint == null) {
				//or add Bad Character Glyph
				int ch = (int) textContent.charAt(0);
				if (pdf2svgConverter.useXMLLogger && !charWasLogged) {
					pdf2svgConverter.xmlLogger.newCharacter(fontName, fontFamilyName, charname, ch);
					charWasLogged = true;
				}
				else
					LOG.error("Cannot convert character: "+textContent+" char: "+ch+" charname: "+charname+" fn: "+fontFamilyName);
				textContent = ""+AMIFontManager.getUnknownCharacterSymbol()+ch;
			} else {
				Integer codepoint = codePoint.getUnicodeDecimal();
				textContent = ""+(char)(int) codepoint;
				if (pdf2svgConverter.useXMLLogger && pdf2svgConverter.xmlLoggerLogMore && !charWasLogged) {
					int ch = (int) textContent.charAt(0);
					pdf2svgConverter.xmlLogger.newCharacter(fontName, fontFamilyName, charname, ch);
					charWasLogged = true;
				}
			}
		}
	}

	private void captureAndIndexGlyphVector(TextPosition text, int charCode) {
		String key = charname;
		if (key == null) {
			key = "" + charCode;
		}
		String pathString = amiFont.getPathStringByCharnameMap().get(key);
		LOG.trace("charname: "+charname+" path: "+pathString);
		if (pathString == null) {
			ensurePageSize();
			PDFGraphics2D graphics = new PDFGraphics2D(amiFont);
			Matrix textPos = text.getTextPos().copy();
			float x = textPos.getXPosition();
			// the 0,0-reference has to be moved from the lower left (PDF) to
			// the upper left (AWT-graphics)
			float y = pageSize.height - textPos.getYPosition();
			// Set translation to 0,0. We only need the scaling and shearing
			textPos.setValue(2, 0, 0);
			textPos.setValue(2, 1, 0);
			// because of the moved 0,0-reference, we have to shear in the
			// opposite direction
			textPos.setValue(0, 1, (-1) * textPos.getValue(0, 1));
			textPos.setValue(1, 0, (-1) * textPos.getValue(1, 0));
			AffineTransform at = textPos.createAffineTransform();
			PDMatrix fontMatrix = pdFont.getFontMatrix();
			// matrix is r00 r01 r10 r11 t0 t1
			double r00 = fontMatrix.getValue(0, 0) * 1000f; 
			double r11 = fontMatrix.getValue(1, 1) * 1000f; 
			LOG.trace("scales: "+r00+"/"+r11);
			at.scale(r00, r11);
			// TODO setClip() is a massive performance hot spot. Investigate
			// optimization possibilities
			if (graphicsState == null) {
				LOG.debug("NULL graphics state");
//				return;
			} else {
				graphics.setClip(graphicsState.getCurrentClippingPath());
			}
			// the fontSize is no longer needed as it is already part of the
			// transformation
			// we should remove it from the parameter list in the long run
			try {
				pdFont.drawString(text.getCharacter(), text.getCodePoints(),
						graphics, 1, at, x, y);
			} catch (IOException e) {
				throw new RuntimeException("font.drawString", e);
			}
			pathString = graphics.getCurrentPathString();
			LOG.trace(charname+": created "+pathString);
			amiFont.getPathStringByCharnameMap().put(key, pathString);
		}
		LOG.trace("pathString: "+pathString);
	}

	private void addTooltips(SVGText svgText, int charCode) {
		if (pdf2svgConverter.addTooltipDebugTitles) {
			String enc = (encoding == null) ? null : encoding.getClass().getSimpleName();
			enc =(enc != null && enc.endsWith(AMIFont.ENCODING)) ? enc.substring(0, enc.length()-AMIFont.ENCODING.length()) : enc;
			String title = "char: "+charCode+"; name: "+charname+"; f: "+fontFamilyName+"; fn: "+fontName+"; e: "+enc;
			SVGTitle svgTitle = new SVGTitle(title);
			svgText.appendChild(svgTitle);
		}
	}

	private int addCodePointToHighPoints(TextPosition text) {
		pdf2svgConverter.ensureCodePointSets();
		int charCode = text.getCharacter().charAt(0);
		if (charCode > 255) {
			if (pdf2svgConverter.knownCodePointSet.containsKey((Integer)charCode)) {
				// known
			} else if (pdf2svgConverter.newCodePointSet.containsKey((Integer) charCode)) {
				// known 
			} else if (encoding != null) {
				pdf2svgConverter.newCodePointSet.ensureEncoding(encoding.toString());
				CodePoint codePoint = new CodePoint((Integer)charCode, charname); // creates as UNKNOWN unicode
				pdf2svgConverter.newCodePointSet.add(codePoint);
				LOG.debug("added to new codePointSet: "+charCode);
			} else {
				LOG.warn("Font name: "+fontName+" No encoding, so cannot add codePoint ("+charCode+") to codePointSet");
			}
		}
		return charCode;
	}

	private void addAttributesToSVGText(float width, SVGText svgText) {
		//svgText.setClipPath(clipString);
		setClipPath(svgText, clipString, (Integer) integerByClipStringMap.get(clipString));
		//svgText.setFontSize(currentFontSize);
		//final String stroke = getCSSColor((Color) paint);
		//svgText.setStroke(stroke);
		//svgText.setFontStyle(currentFontStyle);
		svgText.setFontFamily(fontFamilyName);
		setFontName(svgText, fontName);
		setCharacterWidth(svgText, width);
		svgText.format(nPlaces);
	}

//	private void processDictionaryEncoding(int charCode, SVGText svgText) {
//		LOG.trace("DICT_ENCODE "+fontName+" / "+fontFamilyName+" / "+fontSubType+" / "+charCode+" / "+charname);
//		Integer charCodeNew = fontFamily.convertSymbol2UnicodePoint(charname);
//		if (charCodeNew == null) {
//			charCodeNew = convertCharacterHack(charCode, svgText, "DICT_ENCODE");
//		}
//		if (charCodeNew != null) {
//			if (charCodeNew != charCode) {
//				LOG.warn("Inconsistent charCodes (orig: "+charCode+"("+(char)charCode+"); new "+charCodeNew+"("+(char)(int)charCodeNew+");) for charname "+charname+"; taking old: ");
//				charCodeNew = charCode;
//			}
//			addCharacterData(charCode, svgText, charCodeNew);
//		} else {
//			LOG.error("Cannot find character in dictionary font ("+fontName+"): "+charname+" / "+charCode);
//		}
//	}

//	private void addCharacterData(int charCode, SVGText svgText, Integer charCodeNew) {
//		if (textContent.length() == 1) {
//			textContent = ""+(char)(int)charCodeNew;
//		}
//		annotateContent(svgText, textContent, charCode, charname, charCodeNew, encoding);
//		LOG.trace("charname: "+charname+" charCode: "+charCodeNew+" textContent: "+textContent);
//	}

//	private Integer convertCharacterHack(Integer charCode, SVGText svgText, String title) {
//		Integer charCodeNew = null;
//		
////		charCodeNew = amiFontManager.convertSymbol2UnicodeHack(charname, fontFamilyName);
//		charCodeNew = (fontFamily != null) ? (Integer) fontFamily.convertSymbol2UnicodePoint(charname) : null;
//		if (charCodeNew != null) {
//			LOG.trace(title+" "+fontName+" / "+fontFamilyName+" / "+fontSubType+" / "+charCode+" / "+charname +" / "+(char) (int) charCode+ " new: "+charCodeNew);
//		} else {
//			// horrible hack. Some fonts report only the charCode and not the name, so guess unicode
//			if (charCode != null && charCode > 127) {
//				charCodeNew = charCode;
//			} else {
//				LOG.debug(title+" unconverted "+fontName+" / "+fontFamilyName+" / "+fontSubType+" / "+charCode+" / "+charname +" / "+(char) (int) charCode);
//			}
//			svgText.setFontSize(20.0);
//			svgText.setFill("blue");
//		}
//		return charCodeNew;
//	}

	/** this font is declared as a symbol font. That means we have to work out what each character means
	 * MathematicalPI has a completely different set of codes and names so needs lookup
	 * some "symbol fonts" appear to be largely unicode
	 * 
	 * This is similar to the dictionaryEncoded stuff as they both seem to be ab/used similarly
	 * @param charCode
	 * @param svgText
	 */
//	private void convertSymbolsToCharacters(Integer charCode, SVGText svgText) {
//		LOG.trace("SYMBOL "+fontName+" / "+fontFamilyName+" / "+fontSubType+" / "+charCode+" / "+charname);
//		Integer charCodeNew = amiFontManager.convertSymbol2UnicodeStandard(charname);
//		if (charCodeNew == null) {
//			charCodeNew = convertCharacterHack(charCode, svgText, "SYMBOL_ENCODE");
//		}
//		if (charCodeNew != null) {
//			addCharacterData(charCode, svgText, charCodeNew);
//		} else {
//			LOG.error("Cannot find character in symbol font ("+fontName+"): "+charname+" / "+charCode);
//		}
//	}
	
	private void annotateContent(SVGText svgText, String unicodeContent, int charCode, String charname, int newCode, Encoding fontEncoding) {
		try {
			svgText.setText(unicodeContent);
		} catch (Exception e) {
//			if (pdf2svgConverter.useXMLLogger && !charWasLogged) {
//				pdf2svgConverter.xmlLogger.newCharacter(fontName, fontFamilyName, charname, charCode);
//				charWasLogged = true;
//			}
//			else
				LOG.error("couldn't set unicode: "+unicodeContent+" / +font: "+fontName+" charname: "+charname+" "+charCode+" / "+e);
			svgText.setText("?"+(int)charCode);
		}
		if (unicodeContent.length() > 1) {
			PDF2SVGUtil.setSVGXAttribute(svgText, PDF2SVGUtil.LIGATURE, ""+unicodeContent.length());
		}
		PDF2SVGUtil.setSVGXAttribute(svgText, PDF2SVGUtil.CHARACTER_CODE, ""+charCode);
		String fontEnc = (fontEncoding == null) ? "null" : fontEncoding.getClass().getSimpleName();
		if (fontEnc.endsWith("Encoding")) {
			fontEnc = fontEnc.substring(0, fontEnc.length()-"Encoding".length());
		}
		PDF2SVGUtil.setSVGXAttribute(svgText, PDF2SVGUtil.FONT_ENCODING, ""+fontEnc);
		if (charname != null) {
			PDF2SVGUtil.setSVGXAttribute(svgText, PDF2SVGUtil.CHARACTER_NAME, ""+charname);
		}
		if (newCode != charCode) {
			PDF2SVGUtil.setSVGXAttribute(svgText, PDF2SVGUtil.CHARACTER_NEW_CODE, ""+newCode);
		}
		svgText.setFill("red");
		svgText.setStrokeWidth(0.15);
		svgText.setStroke("blue");
		svgText.setFontSize(20.0);
		if (charCode == AMIFontManager.UNKNOWN_CHAR) {
			svgText.setStrokeWidth(3.0);
		}
		if (pdf2svgConverter.useXMLLogger && pdf2svgConverter.xmlLoggerLogMore && !charWasLogged) {
			pdf2svgConverter.xmlLogger.newCharacter(fontName, fontFamilyName, charname, charCode);
			charWasLogged = true;
		}
	}

	private String getAndFormatClipPath() {
		Shape shape = getGraphicsState().getCurrentClippingPath();
		SVGPath path = new SVGPath(shape);
		path.format(nPlaces);
		clipString = path.getDString();
		// old approach
		ensureClipStringSet();
		clipStringSet.add(clipString);
		// new approach
		ensureIntegerByClipStringMap();
		if (!integerByClipStringMap.containsKey(clipString)) {
			integerByClipStringMap.put(clipString, integerByClipStringMap.size()+1); // count from 1
		}
		return clipString;
	}

	private void ensureIntegerByClipStringMap() {
		if (integerByClipStringMap == null) {
			integerByClipStringMap = new HashMap<String, Integer>();
		}
	}

	private void ensureClipStringSet() {
		if (clipStringSet == null) {
			clipStringSet = new HashSet<String>();
		}
	}

	private float getCharacterWidth(PDFont font, String textContent) {
		float width = 0.0f;
		try {
			width = font.getStringWidth(textContent);
		} catch (IOException e) {
			throw new RuntimeException("PDFBox exception ", e);
		}
		return width;
	}

	private void annotateUnusualCharacters(TextPosition text, SVGText svgText) {
		char cc = text.getCharacter().charAt(0);
		String s = AMIFontManager.BADCHAR_S+(int)cc+AMIFontManager.BADCHAR_E;
		if (pdf2svgConverter.useXMLLogger && !charWasLogged) {
			pdf2svgConverter.xmlLogger.newCharacter(fontName, fontFamilyName, charname, cc);
			charWasLogged = true;
		}
		else
			LOG.debug(s+" "+fontName+" ("+fontSubType+") charname: "+charname);
		s = ""+(char)(BADCHAR+Math.min(9, cc));
		svgText.setText(s);
		svgText.setStroke("red");
		svgText.setFill("red");
		svgText.setFontFamily("Helvetica");
		svgText.setStrokeWidth(0.5);
	}

	/** translates java color to CSS RGB
	 * 
	 * @param paint
	 * @return CCC as #rrggbb (alpha is currently discarded)
	 */
	private static String getCSSColor(Paint paint) {
		String colorS = null;
		if (paint instanceof Color) {
			int r = ((Color) paint).getRed();
			int g = ((Color) paint).getGreen();
			int b = ((Color) paint).getBlue();
			// int a = ((Color) paint).getAlpha();
			int rgb = (r<<16)+(g<<8)+b;
			colorS = String.format("#%06x", rgb);
			if (rgb != 0) {
				LOG.trace("Paint "+rgb+" "+colorS);
			}
		}
		return colorS;
	}

	private double getFontSizeAndSetNotZeroRotations(SVGText svgText) {
		// attempts to see if matrices were scaling glyphs - apparently not.
		AffineTransform at = textPos.createAffineTransform();
//		double atScaleX = at.getScaleX();
//		double atScaleY = at.getScaleY();
//		double atScaleRatio = atScaleX/atScaleY;
//		if (!Real.isEqual(1.0, atScaleRatio, eps)) {
//			double[] dd = new double[9];
//			at.getMatrix(dd);
//			LOG.debug(svgText.getText()+" / atScaleRatio "+atScaleRatio+"/"+atScaleX+"/"+atScaleY+"/"+new RealArray(dd));
//		}		
		PDMatrix fontMatrix = pdFont.getFontMatrix();
		at.scale(fontMatrix.getValue(0, 0) * 1000f,
				fontMatrix.getValue(1, 1) * 1000f);
//		RealMatrix realMatrix = getRealMatrix(fontMatrix);
		double scalex = at.getScaleX();
		double scaley = at.getScaleY();
//		double scaleRatio = scalex/scaley;
//		double eps = 0.00000001;
//		if (Real.isZero(scalex, eps) || Real.isZero(scaley, eps)) {
//			LOG.debug("zero scales: "+fontMatrix.getValue(0, 0)+"/"+fontMatrix.getValue(0, 1)+"/"+fontMatrix.getValue(1, 0)+"/"+fontMatrix.getValue(1, 1)+"/"+fontMatrix.getValue(0, 2)+"/"+fontMatrix.getValue(1, 2)+"/");
//			LOG.debug("RM "+realMatrix);
//		} else if (!Real.isEqual(1.0, atScaleRatio, eps)) {
//			LOG.debug("scaleRatio "+atScaleRatio+"/"+atScaleY+"/"+atScaleY);
//			LOG.debug("RM "+realMatrix);
//		}		
		double scale = Math.sqrt(scalex * scaley);
		Transform2 t2 = new Transform2(at);
		
		int angleDeg =0;
		Angle angle = t2.getAngleOfRotation();
		if (angle != null) {
			angleDeg = Math.round((float)angle.getDegrees());
		}
		if (angleDeg != 0) {
			LOG.trace("Transform "+t2+" "+svgText.getText()+" "+at+" "+PDF2SVGUtil.getRealArray(fontMatrix));
			// do this properly later (only if scales are anisotropic and so far no evidence)
			scale = Math.sqrt(Math.abs(t2.elementAt(0, 1)*t2.elementAt(1, 0)));
			Transform2 t2a = Transform2.getRotationAboutPoint(angle, svgText.getXY());
			svgText.setTransform(t2a);
		}
		svgText.setFontSize(scale);
		return scale;
	}

	/** changes coordinates because AWT and SVG use top-left origin while PDF uses bottom left
	 * 
	 * @param text
	 * @param svgText
	 */
	private void createAndReOrientateTextPosition(TextPosition text, SVGText svgText) {
		ensurePageSize();
		textPos = text.getTextPos().copy();
		float x = textPos.getXPosition();
		// the 0,0-reference has to be moved from the lower left (PDF) to
		// the upper left (AWT-graphics)
		float y = pageSize.height - textPos.getYPosition();
		// Set translation to 0,0. We only need the scaling and shearing
		textPos.setValue(2, 0, 0);
		textPos.setValue(2, 1, 0);
		// because of the moved 0,0-reference, we have to shear in the
		// opposite direction
		textPos.setValue(0, 1, (-1) * textPos.getValue(0, 1));
		textPos.setValue(1, 0, (-1) * textPos.getValue(1, 0));
		currentXY = new Real2(x, y);
		svgText.setXY(currentXY);
	}

	private void createGraphicsStateAndPaintAndComposite(SVGText svgText) {
		Paint paint;
		try {
			graphicsState = getGraphicsState();
			ensurePageSize();
			switch (graphicsState.getTextState().getRenderingMode()) {
			case PDTextState.RENDERING_MODE_FILL_TEXT:
				// composite = graphicsState.getNonStrokeJavaComposite();
				paint = graphicsState.getNonStrokingColor().getJavaColor();
				if (paint == null) {
					paint = graphicsState.getNonStrokingColor().getPaint(pageSize.height);
				}
				svgText.setFill(getCSSColor(paint));
				break;
			case PDTextState.RENDERING_MODE_STROKE_TEXT:
				// composite = graphicsState.getStrokeJavaComposite();
				paint = graphicsState.getStrokingColor().getJavaColor();
				if (paint == null) {
					paint = graphicsState.getStrokingColor().getPaint(pageSize.height);
				}
				Double lineWidth = graphicsState.getLineWidth();
				svgText.setStroke(getCSSColor(paint));
				svgText.setStrokeWidth(lineWidth);
				break;
			case PDTextState.RENDERING_MODE_NEITHER_FILL_NOR_STROKE_TEXT:
				// basic support for text rendering mode "invisible"
				Color nsc = graphicsState.getStrokingColor().getJavaColor();
				float[] components = { Color.black.getRed(),
						Color.black.getGreen(), Color.black.getBlue() };
				paint = new Color(nsc.getColorSpace(), components, 0f);
				// composite = graphicsState.getStrokeJavaComposite();
				break;
			default:
				// TODO : need to implement....
				LOG.trace("Unsupported RenderingMode "
						+ this.getGraphicsState().getTextState()
								.getRenderingMode()
						+ " in PageDrawer.processTextPosition()."
						+ " Using RenderingMode "
						+ PDTextState.RENDERING_MODE_FILL_TEXT + " instead");
				// composite = graphicsState.getNonStrokeJavaComposite();
				paint = graphicsState.getNonStrokingColor().getJavaColor();
				svgText.setFill(getCSSColor(paint));
			}
		} catch (IOException e) {
			throw new RuntimeException("graphics state error???", e);
		}
	}

	/** traps any remaining unimplemented PageDrawer calls
	 * 
	 */
	public Graphics2D getGraphics() {
		LOG.error("getGraphics was called!!!!!!! (May mean method was not overridden) %n");
		return null;
	}

	public void fillPath(int windingRule) throws IOException {
		PDColorState colorState = getGraphicsState().getNonStrokingColor();
		Paint currentPaint = getCurrentPaint(colorState, "non-stroking");
		createAndAddSVGPath(windingRule, currentPaint);
	}

	public void strokePath() throws IOException {
		PDColorState colorState = getGraphicsState().getStrokingColor(); 
		Paint currentPaint = getCurrentPaint(colorState, "stroking");
		Integer windingRule = null;
		createAndAddSVGPath(windingRule, currentPaint);
	}

	/** processes both stroke and fill for paths
	 * 
	 * @param windingRule if not null implies fill else stroke
	 * @param currentPaint
	 */
	private void createAndAddSVGPath(Integer windingRule, Paint currentPaint) {
//		renderIntent = getGraphicsState().getRenderingIntent(); // probably ignorable at first (converts color maps)
		dashPattern = getGraphicsState().getLineDashPattern();
		lineWidth = getGraphicsState().getLineWidth();
//		PDTextState textState = getGraphicsState().getTextState();  // has things like character and word spacings // not yet used
		GeneralPath generalPath = getLinePath();
		if (windingRule != null) {
			generalPath.setWindingRule(windingRule);
		}
		SVGPath svgPath = new SVGPath(generalPath);
		clipString = getAndFormatClipPath();
		svgPath.setClipPath(clipString);
		setClipPath(svgPath, clipString, integerByClipStringMap.get(clipString));
		if (windingRule != null) {
			svgPath.setFill(getCSSColor(currentPaint));
		} else {
			svgPath.setStroke(getCSSColor(currentPaint));
		}
		if (dashPattern != null) {
			setDashArray(svgPath);
		}
		svgPath.setStrokeWidth(lineWidth);
		svgPath.format(nPlaces);
		svg.appendChild(svgPath);
		generalPath.reset();
	}

	private void setClipPath(SVGElement svgElement, String clipString, Integer clipPathNumber) {
		String urlString = "url(#clipPath"+clipPathNumber+")";
		svgElement.setClipPath(urlString);
	}

	private void setDashArray(SVGPath svgPath) {
		@SuppressWarnings("unchecked")
		List<Integer> dashIntegerList = (List<Integer>) dashPattern.getDashPattern();
		StringBuilder sb = new StringBuilder("");
		LOG.trace("COS ARRAY "+dashIntegerList.size());
		if (dashIntegerList.size() > 0) {
			for (int i = 0; i < dashIntegerList.size(); i++) {
				if (i > 0) {
					sb.append(" ");
				}
				sb.append(dashIntegerList.get(i));
			}
			svgPath.setStrokeDashArray(sb.toString());
			LOG.trace("dash "+dashPattern);
		}
	}

	private Paint getCurrentPaint(PDColorState colorState, String type) throws IOException {
		Paint currentPaint = colorState.getJavaColor();
		if (currentPaint == null) {
			currentPaint = colorState.getPaint(pageSize.height);
		}
		if (currentPaint == null) {
			LOG.trace("ColorSpace "
					+ colorState.getColorSpace().getName()
					+ " doesn't provide a " + type
					+ " color, using white instead!");
			currentPaint = Color.WHITE;
		}
		return currentPaint;
	}

	/** maye be removed later
	 * @throws IOException 
	 * 
	 */
	@Override
	public void drawImage(Image awtImage, AffineTransform at) {
//		System.out
//				.printf("\tdrawImage: awtImage='%s', affineTransform='%s', composite='%s', clip='%s'%n",
//						awtImage.toString(), at.toString(), getGraphicsState()
//								.getStrokeJavaComposite().toString(),
//						getGraphicsState().getCurrentClippingPath().toString());
		if (awtImage instanceof BufferedImage) {
			Transform2 t2 = new Transform2(at);
			BufferedImage bImage = (BufferedImage) awtImage;
			LOG.trace("IMAGE: x="+bImage.getMinX()+" y="+bImage.getMinY()+" h="+bImage.getHeight()+" w="+bImage.getWidth());
			SVGImage svgImage = new SVGImage();
			svgImage.setTransform(t2);
			svgImage.readImageData(bImage, SVGImage.IMAGE_PNG);
			svg.appendChild(svgImage);
		} else {
			LOG.warn("Image not incorporated");
		}
	}

	/** used in pageDrawer - shaded type of fill
	 * 
	 */
	@Override
	public void shFill(COSName shadingName) throws IOException {
		LOG.trace("Shading Fill Not Implemented");
	}

	/** creates new <svg> and removes/sets some defaults
	 * this is partly beacuse SVGFoo may have defaults (bad idea?)
	 */
	public void createSVGSVG() {
		this.svg = new SVGSVG();
		svg.setWidth(pdf2svgConverter.pageWidth);
		svg.setHeight(pdf2svgConverter.pageHeight);
		svg.setStroke("none");
		svg.setStrokeWidth(0.0);
		svg.addNamespaceDeclaration(PDF2SVGUtil.SVGX_PREFIX, PDF2SVGUtil.SVGX_NS);
		clipStringSet = new HashSet<String>();
	}

	public SVGSVG getSVG() {
		return svg;
	}

	private void setFontName(SVGElement svgElement, String fontName) {
		if (fontName != null) {
			PDF2SVGUtil.setSVGXAttribute(svgElement, AMIFontManager.FONT_NAME, fontName);
		} else {
			LOG.error("NULL font name");
		}
	}
	
	private void setCharacterWidth(SVGElement svgElement, double width) {
		PDF2SVGUtil.setSVGXAttribute(svgElement, PDF2SVGUtil.CHARACTER_WIDTH, ""+width);
	}
	
	@Override
	public void setStroke(BasicStroke basicStroke) {
		this.basicStroke = basicStroke;
	}

	@Override
	public BasicStroke getStroke() {
		return basicStroke;
	}

}
