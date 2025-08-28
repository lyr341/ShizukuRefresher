@ECHO OFF
IF NOT EXIST "gradle\wrapper\gradle-wrapper.properties" (
  ECHO No wrapper properties. Trying 'gradle wrapper'...
  gradle wrapper
)
CALL "%~dp0gradlew" %*
