import com.cloudbees.groovy.cps.CpsTransformer
import org.codehaus.groovy.control.CompilerConfiguration
def repo=new File(args[0])
def config=new CompilerConfiguration()
config.sourceEncoding='UTF-8'
config.addCompilationCustomizers(new CpsTransformer())
def loader=new GroovyClassLoader(this.class.classLoader,config)
new File(repo,'vars').eachFileMatch(~/.*[.]groovy/) { file -> loader.parseClass(file) }
def plain=new GroovyClassLoader(this.class.classLoader)
new File(repo,'tools').eachFileMatch(~/.*[.]groovy/) { file -> plain.parseClass(file) }
println 'PASS compilation: all shared vars CPS-compiled against installed Jenkins/plugin classes; admin tools compiled'
