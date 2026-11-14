package kokomux.app

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import kokomux.engine.ming.GraphCall
import kokomux.engine.ming.MingGraphs

/**
 * Ming's graphs on the phone's NPU, through LiteRT and Qualcomm's libraries
 * (in the app: libLiteRtDispatch_Qualcomm and the compiler plugin, and QNN's
 * runtime for the Hexagon v81). The first time each graph loads it's compiled
 * for this phone (about a minute in all), one graph at a time so the memory
 * it takes doesn't pile up; LiteRT keeps the result, and later loads take
 * seconds.
 */
object MingNpu {
    /** One signature of a compiled graph, its buffers made once. */
    private class Graph(private val model: CompiledModel, signatures: Map<String, Pair<Int, Int>>) : GraphCall {
        private val ins = signatures.mapValues { (sig, io) -> Array(io.first) { model.createInputBuffer("args_$it", sig) } }
        private val outs = signatures.mapValues { (sig, io) -> Array(io.second) { model.createOutputBuffer("output_$it", sig) } }
        private val inMaps = ins.mapValues { (_, a) -> a.withIndex().associate { "args_${it.index}" to it.value } }
        private val outMaps = outs.mapValues { (_, a) -> a.withIndex().associate { "output_${it.index}" to it.value } }

        override fun call(sig: String, inputs: Array<FloatArray>, read: IntArray?): Array<FloatArray?> {
            val i = ins.getValue(sig)
            for (k in i.indices) i[k].writeFloat(inputs[k])
            model.run(inMaps.getValue(sig), outMaps.getValue(sig), sig)
            val o = outs.getValue(sig)
            val res = arrayOfNulls<FloatArray>(o.size)
            for (k in read ?: IntArray(o.size) { it }) res[k] = o[k].readFloat()
            return res
        }

        override fun close() {
            (ins.values + outs.values).forEach { a -> a.forEach(TensorBuffer::close) }
            model.close()
        }
    }

    private var env: Environment? = null

    /** LiteRT's environment, pointed at the NPU's libraries in the app. */
    @Synchronized
    private fun environment(context: Context): Environment = env ?: run {
        val libs = context.applicationInfo.nativeLibraryDir
        Environment.create(context, mapOf(
            Environment.Option.DispatchLibraryDir to libs,
            Environment.Option.CompilerPluginLibraryDir to libs,
        )).also {
            check(Accelerator.NPU in it.getAvailableAccelerators()) { "Ming needs the phone's NPU, and LiteRT can't find it" }
            env = it
        }
    }

    private fun load(context: Context, dir: File, role: String): Graph {
        val options = CompiledModel.Options(Accelerator.NPU)
        // Without BURST the NPU runs at its default clock, several times slower.
        options.qualcommOptions = CompiledModel.QualcommOptions(
            htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.BURST,
        )
        val model = CompiledModel.create(File(dir, MingGraphs.FILES.getValue(role)).absolutePath, options, environment(context))
        return Graph(model, MingGraphs.SIGNATURES.getValue(role))
    }

    /**
     * The five graphs, loaded. On the first load (no [ready] stamp in [dir]) each is compiled on
     * its own first, [preparing] told (graph, of).
     */
    fun graphs(context: Context, dir: File, preparing: (Int, Int) -> Unit): MingGraphs {
        val roles = MingGraphs.FILES.keys.toList()
        val stamp = File(dir, ".compiled")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!stamp.exists() || stamp.readText() != version) {
            for ((i, role) in roles.withIndex()) {
                preparing(i + 1, roles.size)
                load(context, dir, role).close()
            }
            stamp.writeText(version)
        }
        val g = roles.associateWith { load(context, dir, it) }
        return MingGraphs(g.getValue("prefill"), g.getValue("step"), g.getValue("flow"), g.getValue("agg"), g.getValue("dec"))
    }
}
