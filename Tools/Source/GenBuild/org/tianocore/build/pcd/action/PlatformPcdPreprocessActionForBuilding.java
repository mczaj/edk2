/** @file
  PlatformPcdPreprocessActionForBuilding class.

  This action class is to collect PCD information from MSA, SPD, FPD xml file.
  This class will be used for wizard and build tools, So it can *not* inherit
  from buildAction or wizardAction.

Copyright (c) 2006, Intel Corporation
All rights reserved. This program and the accompanying materials
are licensed and made available under the terms and conditions of the BSD License
which accompanies this distribution.  The full text of the license may be found at
http://opensource.org/licenses/bsd-license.php

THE PROGRAM IS DISTRIBUTED UNDER THE BSD LICENSE ON AN "AS IS" BASIS,
WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND, EITHER EXPRESS OR IMPLIED.

**/
package org.tianocore.build.pcd.action;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.tianocore.DynamicPcdBuildDefinitionsDocument.DynamicPcdBuildDefinitions;
import org.tianocore.PcdBuildDefinitionDocument;
import org.tianocore.PlatformSurfaceAreaDocument;
import org.tianocore.build.exception.PlatformPcdPreprocessBuildException;
import org.tianocore.build.fpd.FpdParserTask;
import org.tianocore.build.global.GlobalData;
import org.tianocore.build.id.FpdModuleIdentification;
import org.tianocore.pcd.action.ActionMessage;
import org.tianocore.pcd.action.PlatformPcdPreprocessAction;
import org.tianocore.pcd.entity.MemoryDatabaseManager;
import org.tianocore.pcd.entity.ModulePcdInfoFromFpd;
import org.tianocore.pcd.entity.Token;
import org.tianocore.pcd.entity.UsageIdentification;
import org.tianocore.pcd.exception.EntityException;
import org.tianocore.pcd.exception.PlatformPcdPreprocessException;

/**
   This action class is to collect PCD information from MSA, SPD, FPD xml file.
   This class will be used for wizard and build tools, So it can *not* inherit
   from buildAction or UIAction.
**/
public class PlatformPcdPreprocessActionForBuilding extends PlatformPcdPreprocessAction {
    ///
    /// FPD file is the root file.
    ///
    private String                      fpdFilePath;

    ///
    /// Message level for CollectPCDAction.
    ///
    private int                         originalMessageLevel;

    ///
    /// Cache the fpd docment instance for private usage.
    ///
    private PlatformSurfaceAreaDocument fpdDocInstance;

    /**
      Set action message level for CollectPcdAction tool.

      The message should be restored when this action exit.

      @param actionMessageLevel parameter for this action
    **/
    public void setActionMessageLevel(int actionMessageLevel) {
        originalMessageLevel       = ActionMessage.messageLevel;
        ActionMessage.messageLevel = actionMessageLevel;
    }

    /**
      Set FPDFileName parameter for this action class.

      @param fpdFilePath    fpd file path
    **/
    public void setFPDFilePath(String fpdFilePath) {
        this.fpdFilePath = fpdFilePath;
    }

    /**
      Common function interface for outer.

      @param fpdFilePath    The fpd file path of current build or analysis.
      @param messageLevel   The message level for this Action.

      @throws  PlatformPreprocessBuildException 
                            The exception of this function. Because it can *not* be predict
                            where the action class will be used. So only Exception can be throw.

    **/
    public void perform(String fpdFilePath, int messageLevel) 
        throws PlatformPcdPreprocessBuildException {
        setFPDFilePath(fpdFilePath);
        setActionMessageLevel(messageLevel);
        checkParameter();
        execute();
        ActionMessage.messageLevel = originalMessageLevel;
    }

    /**
      Core execution function for this action class.

      This function work flows will be:
      1) Collect and prepocess PCD information from FPD file, all PCD
      information will be stored into memory database.
      2) Generate 3 strings for
        a) All modules using Dynamic(Ex) PCD entry.(Token Number)
        b) PEI PCDDatabase (C Structure) for PCD Service PEIM.
        c) DXE PCD Database (C structure) for PCD Service DXE.


      @throws  EntityException Exception indicate failed to execute this action.

    **/
    public void execute() throws PlatformPcdPreprocessBuildException {
        String errorMessageHeader   = "Fail to initialize Pcd memory database for building. Because:";
        String errorsForPreprocess  = null;

        //
        // Get memoryDatabaseManager instance from GlobalData.
        // The memoryDatabaseManager should be initialized as static variable
        // in some Pre-process class.
        //
        setPcdDbManager(GlobalData.getPCDMemoryDBManager());

        //
        // Collect all PCD information defined in FPD file.
        // Evenry token defind in FPD will be created as an token into
        // memory database.
        //
        try {
            initPcdMemoryDbWithPlatformInfo();
        } catch (PlatformPcdPreprocessException exp) {
            throw new PlatformPcdPreprocessBuildException(errorMessageHeader + exp.getMessage());
        }
        errorsForPreprocess = this.getErrorString();
        if (errorsForPreprocess != null) {
            throw new PlatformPcdPreprocessBuildException(errorMessageHeader + "\r\n" + errorsForPreprocess);
        }

        //
        // Generate for PEI, DXE PCD DATABASE's definition and initialization.
        //
        try {
            genPcdDatabaseSourceCode ();
        } catch (EntityException exp) {
            throw new PlatformPcdPreprocessBuildException(errorMessageHeader + exp.getMessage());
        }
    }

    /**
      Override function: implementate the method of get Guid string information from SPD file.

      @param guidCName      Guid CName string.

      @return String        Guid information from SPD file.
      @throws PlatformPcdPreprocessException
                            Fail to get Guid information from SPD file.
    **/
    public String getGuidInfoFromSpd(String guidCName) throws PlatformPcdPreprocessException {
        String tokenSpaceStrRet = null;
        try {
            tokenSpaceStrRet = GlobalData.getGuidInfoFromCname(guidCName);
        } catch ( Exception e ) {
            throw new PlatformPcdPreprocessException ("Failed get Guid CName " + guidCName + "from SPD file!");
        }
        return tokenSpaceStrRet;
    }

    /**
      This function generates source code for PCD Database.

      @throws EntityException  If the token does *not* exist in memory database.

    **/
    private void genPcdDatabaseSourceCode()
        throws EntityException {
        String PcdCommonHeaderString = PcdDatabase.getPcdDatabaseCommonDefinitions();

        ArrayList<Token> alPei = new ArrayList<Token> ();
        ArrayList<Token> alDxe = new ArrayList<Token> ();

        getPcdDbManager().getTwoPhaseDynamicRecordArray(alPei, alDxe);
        PcdDatabase pcdPeiDatabase = new PcdDatabase (alPei, "PEI", 0);
        pcdPeiDatabase.genCode();
        MemoryDatabaseManager.PcdPeimHString        = PcdCommonHeaderString + pcdPeiDatabase.getHString() +
                                                      PcdDatabase.getPcdPeiDatabaseDefinitions();
        MemoryDatabaseManager.PcdPeimCString        = pcdPeiDatabase.getCString();

        PcdDatabase pcdDxeDatabase = new PcdDatabase(alDxe, "DXE", alPei.size());
        pcdDxeDatabase.genCode();
        MemoryDatabaseManager.PcdDxeHString   = MemoryDatabaseManager.PcdPeimHString + pcdDxeDatabase.getHString() +
                                                PcdDatabase.getPcdDxeDatabaseDefinitions();
        MemoryDatabaseManager.PcdDxeCString   = pcdDxeDatabase.getCString();
    }

    /**
      Override function: Get component array from FPD.

      This function maybe provided by some Global class.

      @return List<ModuleInfo>                  the component array.
      @throws PlatformPcdPreprocessException    get all modules in <ModuleSA> in FPD file.

    **/
    public List<ModulePcdInfoFromFpd> getComponentsFromFpd()
        throws PlatformPcdPreprocessException {
        List<ModulePcdInfoFromFpd>                  allModules          = new ArrayList<ModulePcdInfoFromFpd>();
        Map<FpdModuleIdentification, XmlObject>     pcdBuildDefinitions = null;
        UsageIdentification                         usageId             = null;

        pcdBuildDefinitions = GlobalData.getFpdPcdBuildDefinitions();
        if (pcdBuildDefinitions == null) {
            return null;
        }

        //
        // Loop map to retrieve all PCD build definition and Module id
        //
        Iterator item = pcdBuildDefinitions.keySet().iterator();
        while (item.hasNext()){
            FpdModuleIdentification id = (FpdModuleIdentification) item.next();
            usageId                    = new UsageIdentification(id.getModule().getName(),
                                                                 id.getModule().getGuid(),
                                                                 id.getModule().getPackage().getName(),
                                                                 id.getModule().getPackage().getGuid(),
                                                                 id.getArch(),
                                                                 id.getModule().getVersion(),
                                                                 id.getModule().getModuleType());
            allModules.add(
                new ModulePcdInfoFromFpd(
                    usageId, 
                    ((PcdBuildDefinitionDocument)pcdBuildDefinitions.get(id)).getPcdBuildDefinition()));
        }
        return allModules;
    }

    /**
       Override function: Verify the datum value according its datum size and datum type, this
       function maybe moved to FPD verification tools in future.

       @param cName         The token name
       @param moduleName    The module who use this PCD token
       @param datum         The PCD's datum
       @param datumType     The PCD's datum type
       @param maxDatumSize  The max size for PCD's Datum.

       @return String       exception strings.
     */
    public String verifyDatum(String            cName,
                              String            moduleName,
                              String            datum,
                              Token.DATUM_TYPE  datumType,
                              int               maxDatumSize) {
        String      exceptionString = null;
        int         value;
        BigInteger  value64;
        String      subStr;
        int         index;

        if (moduleName == null) {
            moduleName = "section <DynamicPcdBuildDefinitions>";
        } else {
            moduleName = "module " + moduleName;
        }

        if (maxDatumSize == 0) {
            exceptionString = String.format("[FPD file error] You maybe miss <MaxDatumSize> for PCD %s in %s",
                                            cName,
                                            moduleName);
            return exceptionString;
        }

        switch (datumType) {
        case UINT8:
            if (maxDatumSize != 1) {
                exceptionString = String.format("[FPD file error] The datum type of PCD data %s in %s "+
                                                "is UINT8, but datum size is %d, they are not matched!",
                                                 cName,
                                                 moduleName,
                                                 maxDatumSize);
                return exceptionString;
            }

            if (datum != null) {
                try {
                    value = Integer.decode(datum);
                } catch (NumberFormatException nfeExp) {
                    exceptionString = String.format("[FPD file error] The datum for PCD %s in %s is not valid "+
                                                    "digital format of UINT8",
                                                    cName,
                                                    moduleName);
                    return exceptionString;
                }
                if (value > 0xFF) {
                    exceptionString = String.format("[FPD file error] The datum for PCD %s in %s is %s exceed"+
                                                    " the max size of UINT8 - 0xFF",
                                                    cName,
                                                    moduleName,
                                                    datum);
                    return exceptionString;
                }
            }
            break;
        case UINT16:
            if (maxDatumSize != 2) {
                exceptionString = String.format("[FPD file error] The datum type of PCD data %s in %s "+
                                                "is UINT16, but datum size is %d, they are not matched!",
                                                 cName,
                                                 moduleName,
                                                 maxDatumSize);
                return exceptionString;
            }
            if (datum != null) {
                try {
                    value = Integer.decode(datum);
                } catch (NumberFormatException nfeExp) {
                    exceptionString = String.format("[FPD file error] The datum for PCD %s in %s is "+
                                                    "not valid digital of UINT16",
                                                    cName,
                                                    moduleName);
                    return exceptionString;
                }
                if (value > 0xFFFF) {
                    exceptionString = String.format("[FPD file error] The datum for PCD %s in %s is %s "+
                                                    "which exceed the range of UINT16 - 0xFFFF",
                                                    cName,
                                                    moduleName,
                                                    datum);
                    return exceptionString;
                }
            }
            break;
        case UINT32:
            if (maxDatumSize != 4) {
                exceptionString = String.format("[FPD file error] The datum type of PCD data %s in %s "+
                                                "is UINT32, but datum size is %d, they are not matched!",
                                                 cName,
                                                 moduleName,
                                                 maxDatumSize);
                return exceptionString;
            }

            if (datum != null) {
                try {
                    if (datum.length() > 2) {
                        if ((datum.charAt(0) == '0')        &&
                            ((datum.charAt(1) == 'x') || (datum.charAt(1) == 'X'))){
                            subStr = datum.substring(2, datum.length());
                            value64 = new BigInteger(subStr, 16);
                        } else {
                            value64 = new BigInteger(datum);
                        }
                    } else {
                        value64 = new BigInteger(datum);
                    }
                } catch (NumberFormatException nfeExp) {
                    exceptionString = String.format("[FPD file error] The datum for PCD %s in %s is not "+
                                                    "valid digital of UINT32",
                                                    cName,
                                                    moduleName);
                    return exceptionString;
                }

                if (value64.bitLength() > 32) {
                    exceptionString = String.format("[FPD file error] The datum for PCD %s in %s is %s which "+
                                                    "exceed the range of UINT32 - 0xFFFFFFFF",
                                                    cName,
                                                    moduleName,
                                                    datum);
                    return exceptionString;
                }
            }
            break;
        case UINT64:
            if (maxDatumSize != 8) {
                exceptionString = String.format("[FPD file error] The datum type of PCD data %s in %s "+
                                                "is UINT64, but datum size is %d, they are not matched!",
                                                 cName,
                                                 moduleName,
                                                 maxDatumSize);
                return exceptionString;
            }

            if (datum != null) {
                try {
                    if (datum.length() > 2) {
                        if ((datum.charAt(0) == '0')        &&
                            ((datum.charAt(1) == 'x') || (datum.charAt(1) == 'X'))){
                            subStr = datum.substring(2, datum.length());
                            value64 = new BigInteger(subStr, 16);
                        } else {
                            value64 = new BigInteger(datum);
                        }
                    } else {
                        value64 = new BigInteger(datum);
                    }
                } catch (NumberFormatException nfeExp) {
                    exceptionString = String.format("[FPD file error] The datum for PCD %s in %s is not valid"+
                                                    " digital of UINT64",
                                                    cName,
                                                    moduleName);
                    return exceptionString;
                }

                if (value64.bitLength() > 64) {
                    exceptionString = String.format("[FPD file error] The datum for PCD %s in %s is %s "+
                                                    "exceed the range of UINT64 - 0xFFFFFFFFFFFFFFFF",
                                                    cName,
                                                    moduleName,
                                                    datum);
                    return exceptionString;
                }
            }
            break;
        case BOOLEAN:
            if (maxDatumSize != 1) {
                exceptionString = String.format("[FPD file error] The datum type of PCD data %s in %s "+
                                                "is BOOLEAN, but datum size is %d, they are not matched!",
                                                 cName,
                                                 moduleName,
                                                 maxDatumSize);
                return exceptionString;
            }

            if (datum != null) {
                if (!(datum.equalsIgnoreCase("TRUE") ||
                     datum.equalsIgnoreCase("FALSE"))) {
                    exceptionString = String.format("[FPD file error] The datum type of PCD data %s in %s "+
                                                    "is BOOELAN, but value is not 'true'/'TRUE' or 'FALSE'/'false'",
                                                    cName,
                                                    moduleName);
                    return exceptionString;
                }

            }
            break;
        case POINTER:
            if (datum == null) {
                break;
            }

            char    ch     = datum.charAt(0);
            int     start, end;
            String  strValue;
            //
            // For void* type PCD, only three datum is support:
            // 1) Unicode: string with start char is "L"
            // 2) Ansci: String start char is ""
            // 3) byte array: String start char "{"
            //
            if (ch == 'L') {
                start       = datum.indexOf('\"');
                end         = datum.lastIndexOf('\"');
                if ((start > end)           ||
                    (end   > datum.length())||
                    ((start == end) && (datum.length() > 0))) {
                    exceptionString = String.format("[FPD file error] The datum type of PCD %s in %s is VOID* and datum is "+
                                                    "a UNICODE string because start with L\", but format maybe"+
                                                    "is not right, correct UNICODE string is L\"...\"!",
                                                    cName,
                                                    moduleName);
                    return exceptionString;
                }

                strValue    = datum.substring(start + 1, end);
                if ((strValue.length() * 2) > maxDatumSize) {
                    exceptionString = String.format("[FPD file error] The datum type of PCD %s in %s is VOID*, and datum is "+
                                                    "a UNICODE string, but the datum size is %d exceed to <MaxDatumSize> : %d",
                                                    cName,
                                                    moduleName,
                                                    strValue.length() * 2,
                                                    maxDatumSize);
                    return exceptionString;
                }
            } else if (ch == '\"'){
                start       = datum.indexOf('\"');
                end         = datum.lastIndexOf('\"');
                if ((start > end)           ||
                    (end   > datum.length())||
                    ((start == end) && (datum.length() > 0))) {
                    exceptionString = String.format("[FPD file error] The datum type of PCD %s in %s is VOID* and datum is "+
                                                    "a ANSCII string because start with \", but format maybe"+
                                                    "is not right, correct ANSIC string is \"...\"!",
                                                    cName,
                                                    moduleName);
                    return exceptionString;
                }
                strValue    = datum.substring(start + 1, end);
                if ((strValue.length()) > maxDatumSize) {
                    exceptionString = String.format("[FPD file error] The datum type of PCD %s in %s is VOID*, and datum is "+
                                                    "a ANSCI string, but the datum size is %d which exceed to <MaxDatumSize> : %d",
                                                    cName,
                                                    moduleName,
                                                    strValue.length(),
                                                    maxDatumSize);
                    return exceptionString;
                }
            } else if (ch =='{') {
                String[]  strValueArray;

                start           = datum.indexOf('{');
                end             = datum.lastIndexOf('}');
                strValue        = datum.substring(start + 1, end);
                strValue        = strValue.trim();
                if (strValue.length() == 0) {
                    exceptionString = String.format ("[FPD file error] The datum type of PCD %s in %s is VOID*, and "+
                                                     "it is byte array in fact, but '{}' is not valid for NULL datam but"+
                                                     " need use '{0}'",
                                                     cName,
                                                     moduleName);
                    return exceptionString;
                }
                strValueArray   = strValue.split(",");
                for (index = 0; index < strValueArray.length; index ++) {
                    try{
                        value = Integer.decode(strValueArray[index].trim());
                    } catch (NumberFormatException nfeEx) {
                        exceptionString = String.format("[FPD file error] The datum type of PCD %s in %s is VOID*, and "+
                                                         "it is byte array in fact. For every byte in array should be a valid"+
                                                         "byte digital, but element %s is not a valid byte digital!",
                                                         cName,
                                                         moduleName,
                                                         strValueArray[index]);
                        return exceptionString;
                    }
                    if (value > 0xFF) {
                        exceptionString = String.format("[FPD file error] The datum type of PCD %s in %s is VOID*, "+
                                                        "it is byte array in fact. But the element of %s exceed the byte range",
                                                        cName,
                                                        moduleName,
                                                        strValueArray[index]);
                        return exceptionString;
                    }
                }

                if (strValueArray.length > maxDatumSize) {
                    exceptionString = String.format("[FPD file error] The datum type of PCD %s in %s is VOID*, and datum is byte"+
                                                    "array, but the number of bytes is %d which exceed to <MaxDatumSzie> : %d!",
                                                    cName,
                                                    moduleName,
                                                    strValueArray.length,
                                                    maxDatumSize);
                    return exceptionString;
                }
            } else {
                exceptionString = String.format("[FPD file error] The datum type of PCD %s in %s is VOID*. For VOID* type, you have three format choise:\n"+
                                                "1) UNICODE string: like L\"xxxx\";\r\n"+
                                                "2) ANSIC string: like \"xxx\";\r\n"+
                                                "3) Byte array: like {0x2, 0x45, 0x23}\r\n"+
                                                "But the datum in seems does not following above format!",
                                                cName,
                                                moduleName);
                return exceptionString;
            }
            break;
        default:
            exceptionString = String.format("[FPD file error] For PCD entry %s in %s, datum type is unknown, it should be one of "+
                                            "UINT8, UINT16, UINT32, UINT64, VOID*, BOOLEAN",
                                            cName,
                                            moduleName);
            return exceptionString;
        }
        return null;
    }

    /**
       Override function: Get dynamic information for a dynamic PCD from <DynamicPcdBuildDefinition> seciton in FPD file.

       This function should be implemented in GlobalData in future.

       @param token         The token instance which has hold module's PCD information
       @param moduleName    The name of module who will use this Dynamic PCD.

       @return DynamicPcdBuildDefinitions.PcdBuildData
    **/
    public DynamicPcdBuildDefinitions.PcdBuildData getDynamicInfoFromFpd(Token     token,
                                                                         String    moduleName)
        throws PlatformPcdPreprocessException {
        int    index             = 0;
        String exceptionString   = null;
        String dynamicPrimaryKey = null;
        DynamicPcdBuildDefinitions                    dynamicPcdBuildDefinitions = null;
        List<DynamicPcdBuildDefinitions.PcdBuildData> dynamicPcdBuildDataArray   = null;
        String                                        tokenSpaceStrRet           = null;

        //
        // If FPD document is not be opened, open and initialize it.
        // BUGBUG: The code should be moved into GlobalData in future.
        //
        if (fpdDocInstance == null) {
            try {
                fpdDocInstance = (PlatformSurfaceAreaDocument)XmlObject.Factory.parse(new File(fpdFilePath));
            } catch(IOException ioE) {
                throw new PlatformPcdPreprocessException("File IO error for xml file:" + fpdFilePath + "\n" + ioE.getMessage());
            } catch(XmlException xmlE) {
                throw new PlatformPcdPreprocessException("Can't parse the FPD xml fle:" + fpdFilePath + "\n" + xmlE.getMessage());
            }
        }

        dynamicPcdBuildDefinitions = fpdDocInstance.getPlatformSurfaceArea().getDynamicPcdBuildDefinitions();
        if (dynamicPcdBuildDefinitions == null) {
            exceptionString = String.format("[FPD file error] There are no <PcdDynamicBuildDescriptions> in FPD file but contains Dynamic type "+
                                            "PCD entry %s in module %s!",
                                            token.cName,
                                            moduleName);
            putError(exceptionString);
            return null;
        }

        dynamicPcdBuildDataArray = dynamicPcdBuildDefinitions.getPcdBuildDataList();
        for (index = 0; index < dynamicPcdBuildDataArray.size(); index ++) {
            tokenSpaceStrRet = getGuidInfoFromSpd(dynamicPcdBuildDataArray.get(index).getTokenSpaceGuidCName());

            if (tokenSpaceStrRet == null) {
                exceptionString = "Fail to get token space guid for token " + dynamicPcdBuildDataArray.get(index).getCName();
                putError(exceptionString);
                continue;
            }

            dynamicPrimaryKey = Token.getPrimaryKeyString(dynamicPcdBuildDataArray.get(index).getCName(),
                                                          tokenSpaceStrRet);
            if (dynamicPrimaryKey.equalsIgnoreCase(token.getPrimaryKeyString())) {
                return dynamicPcdBuildDataArray.get(index);
            }
        }

        return null;
    }

    /**
       Override function: get all <DynamicPcdBuildDefinition> from FPD file.

       @return List<DynamicPcdBuildDefinitions.PcdBuildData>    All DYNAMIC PCD list in <DynamicPcdBuildDefinitions> in FPD file.
       @throws PlatformPcdPreprocessBuildException              Failure to get dynamic information list.

    **/
    public List<DynamicPcdBuildDefinitions.PcdBuildData>
                                            getAllDynamicPcdInfoFromFpd()
        throws PlatformPcdPreprocessException {
        DynamicPcdBuildDefinitions dynamicPcdBuildDefinitions = null;

        //
        // Open fpd document to get <DynamicPcdBuildDefinition> Section.
        // BUGBUG: the function should be move GlobalData in furture.
        //
        if (fpdDocInstance == null) {
            try {
                fpdDocInstance = (PlatformSurfaceAreaDocument)XmlObject.Factory.parse(new File(fpdFilePath));
            } catch(IOException ioE) {
                throw new PlatformPcdPreprocessException("File IO error for xml file:" + fpdFilePath + "\n" + ioE.getMessage());
            } catch(XmlException xmlE) {
                throw new PlatformPcdPreprocessException("Can't parse the FPD xml fle:" + fpdFilePath + "\n" + xmlE.getMessage());
            }
        }

        dynamicPcdBuildDefinitions = fpdDocInstance.getPlatformSurfaceArea().getDynamicPcdBuildDefinitions();
        if (dynamicPcdBuildDefinitions == null) {
            putError("There is no <DynamicPcdBuildDefinitions> in FPD file!");
            return null;
        }

        return dynamicPcdBuildDefinitions.getPcdBuildDataList();
    }

    /**
      check parameter for this action.

      @throws PlatformPcdPreprocessBuildException  Bad parameter.
    **/
    private void checkParameter() throws PlatformPcdPreprocessBuildException {
        File file = null;

        if (fpdFilePath == null) {
            throw new PlatformPcdPreprocessBuildException("WorkspacePath and FPDFileName should be blank for CollectPCDAtion!");
        }

        if (fpdFilePath.length() == 0) {
            throw new PlatformPcdPreprocessBuildException("WorkspacePath and FPDFileName should be blank for CollectPCDAtion!");
        }

        file = new File(fpdFilePath);

        if(!file.exists()) {
            throw new PlatformPcdPreprocessBuildException("FPD File " + fpdFilePath + " does not exist!");
        }
    }

    /**
      Test case function

      @param argv  parameter from command line
    **/
    public static void main(String argv[]) throws PlatformPcdPreprocessBuildException {
        PlatformPcdPreprocessActionForBuilding ca = new PlatformPcdPreprocessActionForBuilding();
        String projectDir = "x:/edk2";
        ca.setFPDFilePath(projectDir + "/EdkNt32Pkg/Nt32.fpd");
        ca.setActionMessageLevel(ActionMessage.MAX_MESSAGE_LEVEL);
        GlobalData.initInfo("Tools" + File.separator + "Conf" + File.separator + "FrameworkDatabase.db",
                            projectDir,
                            "tools_def.txt");
        System.out.println("After initInfo!");
        FpdParserTask fpt = new FpdParserTask();
        fpt.parseFpdFile(new File(projectDir + "/EdkNt32Pkg/Nt32.fpd"));
        ca.execute();
    }
}
