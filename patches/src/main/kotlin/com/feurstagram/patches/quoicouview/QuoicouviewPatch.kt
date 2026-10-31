package com.feurstagram.patches.quoicouview

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.feurstagram.patches.shared.Constants.COMPATIBILITY_INSTAGRAM
import com.feurstagram.patches.shared.Constants.EXTENSION

private const val QUOICOUVIEW = "Lcom/feurstagram/extension/Quoicouview;"
private const val ROW = "Lcom/feurstagram/extension/StoriesTrayRow;"
private const val ROW_MODEL = "Lcom/feurstagram/extension/StoriesTrayRow\$Model;"
private const val ROW_HOLDER = "Lcom/feurstagram/extension/StoriesTrayRow\$Holder;"

// Instagram's list adapter ("IgRecyclerViewAdapter", the name survives in a
// diagnostics string of its constructor). It is built from a builder holding
// its row types ("item definitions"), and the inbox is one of them.
internal object IgRecyclerViewAdapterFingerprint : Fingerprint(
    name = "<init>",
    strings = listOf("IgRecyclerViewAdapter.mDefinitions"),
    custom = { method, _ -> method.parameterTypes.size == 1 },
)

// The stories tray's tap handler: before opening the viewer it checks that the
// feed fragment is visible, and gives up with these two log events otherwise.
internal object StoryTrayTapFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("fragment_not_visible", "story_pog_blocked"),
)

// The story viewer launcher's environment check, run once the tapped story is
// loaded: the feed fragment must be resumed and the feed visible, or the launch
// is dropped and reported as "ENVIRONMENT_RESUMED" with these two fields.
internal object StoryLaunchEnvironmentFingerprint : Fingerprint(
    returnType = "Z",
    strings = listOf("ENVIRONMENT_RESUMED", "fragment_resumed", "feed_visible"),
)

// The feed tray controller ("MainFeedReelTrayController"), by its story launch
// ("preloadAndLaunchViewer"; both names survive in a tracing section).
internal object StoryTrayLaunchFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("MainFeedReelTrayController.preloadAndLaunchViewer"),
)

// The story viewer launcher's in-tab path: it pushes the viewer fragment into
// the current tab under this back stack name.
internal object StoryViewerPushFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("ReelViewerFragment.BACK_STACK_NAME"),
)

private const val STORY_VIEWER = "Linstagram/features/stories/fragment/ReelViewerFragment;"

// The Direct inbox fragment, by its result handling ("DirectInboxController",
// the name survives in a tracing section).
internal object DirectInboxResultFingerprint : Fingerprint(
    name = "onActivityResult",
    strings = listOf("DirectInboxController.onActivityResult"),
)

private val Method.params get() = parameterTypes.map { it.toString() }

private fun methodRef(reference: Any?) = reference as? MethodReference

@Suppress("unused")
val quoicouviewPatch = bytecodePatch(
    name = "Quoicouview mode",
    description = "Lets the experimental Quoicouview mode put the stories tray at the top of the " +
        "Direct inbox, as a row of the inbox list, and open stories from there the way the feed " +
        "opens them.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    extendWith(EXTENSION)

    execute {
        // --- The adapter, its builder, its row-type and model API -------------------

        val adapterInit = IgRecyclerViewAdapterFingerprint.method
        val adapterType = IgRecyclerViewAdapterFingerprint.classDef.type
        val builderType = adapterInit.params.single()
        val builder = classDefBy(builderType)

        // builder.add(definition): the one single-argument void method taking an
        // Instagram type (its sibling takes a java.util.Collection, the
        // constructor a LayoutInflater).
        val addDefinition = builder.methods.singleOrNull {
            it.name != "<init>" && it.returnType == "V" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0].toString().let { type ->
                    !type.startsWith("Ljava/") && !type.startsWith("Landroid/")
                }
        } ?: throw PatchException("Adapter builder has no add-definition method")
        val definitionType = addDefinition.params.single()
        val definition = classDefBy(definitionType)

        val createRow = definition.methods.singleOrNull {
            it.params == listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;")
        } ?: throw PatchException("Row definition has no create method")
        val holderType = createRow.returnType
        val modelClass = definition.methods.singleOrNull {
            it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/Class;"
        } ?: throw PatchException("Row definition has no model-class method")
        val bindRow = definition.methods.singleOrNull {
            it.returnType == "V" && it.parameterTypes.size == 2 && it.params[0] == holderType
        } ?: throw PatchException("Row definition has no bind method")
        val modelType = bindRow.params[1]

        val holder = classDefBy(holderType)
        if (holder.methods.none { it.name == "<init>" && it.params == listOf("Landroid/view/View;") }) {
            throw PatchException("View holder has no (View) constructor")
        }
        val itemView = holder.fields.singleOrNull {
            it.type == "Landroid/view/View;" && !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: throw PatchException("View holder has no item view field")

        // --- The stories row's holder: the holder base is abstract, so a subclass ---------

        mutableClassDefBy(ROW_HOLDER).apply {
            setSuperClass(holderType)
            methods.removeAll { it.name == "<init>" }
            addBridge(
                "<init>", listOf("Landroid/view/View;"), "V", registers = 2,
                """
                    invoke-direct { p0, p1 }, $holderType-><init>(Landroid/view/View;)V
                    return-void
                """,
                AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
            )
        }

        // --- The stories row type: StoriesTrayRow becomes a row definition ------------

        mutableClassDefBy(ROW).apply {
            setSuperClass(definitionType)
            addBridge(
                createRow.name, createRow.params, holderType, registers = 5,
                """
                    invoke-static { p1, p2 }, $QUOICOUVIEW->createStoriesRow(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;)Landroid/view/View;
                    move-result-object v0
                    new-instance v1, $ROW_HOLDER
                    invoke-direct { v1, v0 }, $ROW_HOLDER-><init>(Landroid/view/View;)V
                    return-object v1
                """,
            )
            addBridge(
                modelClass.name, emptyList(), "Ljava/lang/Class;", registers = 2,
                """
                    const-class v0, $ROW_MODEL
                    return-object v0
                """,
            )
            addBridge(
                bindRow.name, bindRow.params, "V", registers = 4,
                """
                    iget-object v0, p1, $holderType->${itemView.name}:Landroid/view/View;
                    invoke-static { v0 }, $QUOICOUVIEW->bindStoriesRow(Landroid/view/View;)V
                    return-void
                """,
            )
        }

        // --- Its model: StoriesTrayRow.Model becomes a list model ----------------------

        mutableClassDefBy(ROW_MODEL).apply {
            interfaces.add(modelType)
            // getKey() keeps its name in Instagram's interface; the content-equality
            // check of the interface it extends is obfuscated: bridge it.
            for (method in abstractMethods(modelType)) {
                if (method.name == "getKey") continue
                if (method.returnType != "Z" || method.params != listOf("Ljava/lang/Object;")) {
                    throw PatchException("Unexpected list-model method ${method.name}${method.params}")
                }
                addBridge(
                    method.name, method.params, "Z", registers = 3,
                    """
                        invoke-virtual { p0, p1 }, $ROW_MODEL->sameContentAs(Ljava/lang/Object;)Z
                        move-result v0
                        return v0
                    """,
                )
            }
        }

        // --- Register the row type in every adapter built ------------------------------

        adapterInit.apply {
            // At the very top of the constructor no local is live yet: v0 and v1
            // are free. The builder is copied down to v1 because the parameter
            // registers of this large method are out of a plain invoke's reach.
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $QUOICOUVIEW->storiesRowDefinition()Ljava/lang/Object;
                    move-result-object v0
                    if-eqz v0, :keep
                    check-cast v0, $definitionType
                    move-object/from16 v1, p1
                    invoke-virtual { v1, v0 }, $builderType->${addDefinition.name}($definitionType)V
                """,
                ExternalLabel("keep", instructions[0]),
            )
        }

        // --- Hand the inbox's list updates to the extension -----------------------------

        // submit(List) wraps the list in an update object and applies it; the
        // inbox applies its updates directly, so the hook goes on apply.
        val submit = classDefBy(adapterType).methods.singleOrNull {
            it.returnType == "V" && it.params == listOf("Ljava/util/List;")
        } ?: throw PatchException("Adapter has no submit(List)")
        val apply = submit.implementation!!.instructions.mapNotNull {
            methodRef((it as? ReferenceInstruction)?.reference)
        }.singleOrNull {
            it.definingClass == adapterType && it.parameterTypes.size == 1 && it.returnType == "V"
        } ?: throw PatchException("Adapter submit(List) does not apply an update")
        mutableClassDefBy(adapterType).methods.single {
            it.name == apply.name && it.params == apply.parameterTypes.map { p -> p.toString() }
        }.addInstructions(
            0,
            "invoke-static { p0, p1 }, $QUOICOUVIEW->onListUpdate(Ljava/lang/Object;Ljava/lang/Object;)V",
        )

        // --- Let a tap on the tray open the story from the inbox -------------------------

        // Both checks ask whether the feed is the surface the user is looking at;
        // with the tray in the inbox, the inbox is. Each answer goes through
        // Quoicouview.isStoryTrayVisible, which says yes when the tray is on screen.
        StoryLaunchEnvironmentFingerprint.method.apply {
            val returns = instructions.filter { it.opcode == Opcode.RETURN }
            if (returns.isEmpty()) throw PatchException("Story launch environment check has no return")
            returns.reversed().forEach { ret ->
                val register = (ret as OneRegisterInstruction).registerA
                addInstructions(
                    ret.location.index,
                    """
                        invoke-static { v$register }, $QUOICOUVIEW->isStoryTrayVisible(Z)Z
                        move-result v$register
                    """,
                )
            }
        }

        StoryTrayTapFingerprint.method.apply {
            val checks = instructions.filter {
                it.opcode == Opcode.INVOKE_VIRTUAL &&
                    methodRef((it as ReferenceInstruction).reference)?.let { ref ->
                        ref.definingClass == "Landroidx/fragment/app/Fragment;" && ref.name == "isVisible"
                    } == true
            }.map { instructions[it.location.index + 1] }
                .filter { it.opcode == Opcode.MOVE_RESULT }
            if (checks.isEmpty()) throw PatchException("Story tray tap no longer checks the feed's visibility")
            checks.reversed().forEach { result ->
                val register = (result as OneRegisterInstruction).registerA
                addInstructions(
                    result.location.index + 1,
                    """
                        invoke-static { v$register }, $QUOICOUVIEW->isStoryTrayVisible(Z)Z
                        move-result v$register
                    """,
                )
            }
        }

        // --- The tray controller lives with the page that shows its tray ----------------

        // The feed's tray controller follows the feed fragment's lifecycle: its
        // onResume, among others, plays the story viewer's closing animation back
        // into the tray and refreshes the tray, its onPause stops what runs. With
        // the tray in the inbox the feed never resumes, so the inbox's own resume
        // and pause are passed on to it, as the feed's are.
        val controllerType = StoryTrayLaunchFingerprint.classDef.type
        mutableClassDefBy(controllerType).apply {
            listOf("onResume", "onPause").forEach { name ->
                methods.singleOrNull { it.name == name && it.parameterTypes.isEmpty() && it.returnType == "V" }
                    ?: throw PatchException("Tray controller has no $name()")
            }
            methods.filter { it.name == "<init>" }.ifEmpty { throw PatchException("Tray controller has no constructor") }
                .forEach { init ->
                    init.instructions.filter { it.opcode == Opcode.RETURN_VOID }.reversed().forEach { ret ->
                        init.addInstructions(
                            ret.location.index,
                            "invoke-static/range { p0 .. p0 }, $QUOICOUVIEW->onTrayController(Ljava/lang/Object;)V",
                        )
                    }
                }
        }
        mutableClassDefBy(DirectInboxResultFingerprint.classDef.type).apply {
            listOf("onResume" to "trayControllerToResume", "onPause" to "trayControllerToPause").forEach { (name, hook) ->
                val method = methods.singleOrNull { it.name == name && it.parameterTypes.isEmpty() && it.returnType == "V" }
                    ?: throw PatchException("Direct inbox fragment has no $name()")
                // After the page's own handling, as Instagram's fragments notify
                // their lifecycle listeners. Nothing is live at a return-void.
                method.instructions.filter { it.opcode == Opcode.RETURN_VOID }.reversed().forEach { ret ->
                    method.addInstructionsWithLabels(
                        ret.location.index,
                        """
                            invoke-static { }, $QUOICOUVIEW->$hook()Ljava/lang/Object;
                            move-result-object v0
                            if-eqz v0, :done
                            check-cast v0, $controllerType
                            invoke-virtual { v0 }, $controllerType->$name()V
                        """,
                        ExternalLabel("done", ret),
                    )
                }
            }
        }

        // --- The viewer goes over the inbox, not in place of it --------------------------

        // The launcher pushes the viewer into the current tab with a replace: the
        // page beneath is torn down, and built again when the viewer closes. Cheap
        // for the feed, not for the inbox, whose rebuild would hold the closing
        // animation back. A viewer opened from the tray in the inbox is added over
        // it instead, with the navigator's other commit, and the tray controller is
        // told its page is covered, then uncovered when that viewer goes: the
        // pause and resume the replace would have given it.

        // The tray's launches, by their parameters.
        StoryTrayLaunchFingerprint.method.apply {
            val launchParams = instructions.singleOrNull {
                it.opcode == Opcode.INVOKE_DIRECT_RANGE && methodRef((it as ReferenceInstruction).reference)?.let { ref ->
                    ref.name == "<init>" && ref.parameterTypes.map { p -> p.toString() }.let { params ->
                        params.size == 9 && params[1] == "Ljava/lang/Class;" && params.drop(4).all { p -> p == "Z" }
                    }
                } == true
            } ?: throw PatchException("Story tray launch no longer builds its launch parameters")
            val params = (launchParams as RegisterRangeInstruction).startRegister
            addInstructions(
                launchParams.location.index + 1,
                "invoke-static/range { v$params .. v$params }, $QUOICOUVIEW->onTrayLaunch(Ljava/lang/Object;)V",
            )
        }

        val launcher = StoryViewerPushFingerprint.method
        val launcherType = StoryViewerPushFingerprint.classDef.type
        // The navigator: the class the back stack name is stored into.
        val nameStore = launcher.instructions.withIndex().singleOrNull { (index, instruction) ->
            instruction.opcode == Opcode.IPUT_OBJECT && index > 0 &&
                ((launcher.instructions[index - 1] as? ReferenceInstruction)?.reference as? StringReference)
                    ?.string == "ReelViewerFragment.BACK_STACK_NAME"
        }?.value ?: throw PatchException("Story viewer push no longer names its back stack entry")
        val navigatorType = ((nameStore as ReferenceInstruction).reference as FieldReference).definingClass
        // Its commit: the last no-argument call on it.
        val commit = launcher.instructions.lastOrNull {
            it.opcode == Opcode.INVOKE_VIRTUAL && methodRef((it as ReferenceInstruction).reference)?.let { ref ->
                ref.definingClass == navigatorType && ref.parameterTypes.isEmpty() && ref.returnType == "V"
            } == true
        } ?: throw PatchException("Story viewer push no longer commits")
        val replaceCommit = methodRef((commit as ReferenceInstruction).reference)!!
        val navigator = classDefBy(navigatorType)
        // Commits are one-liners into the navigator's core, with an operation:
        // the add one is the commit whose operation leads the core to add the
        // fragment (a transaction call with FragmentTransaction's OP_ADD, 1).
        fun Method.commitOperation(): Pair<FieldReference, MethodReference>? {
            val body = implementation?.instructions?.toList() ?: return null
            if (parameterTypes.isNotEmpty() || returnType != "V" || body.size != 3) return null
            val operation = (body[0] as? ReferenceInstruction)?.reference as? FieldReference ?: return null
            val core = methodRef((body[1] as? ReferenceInstruction)?.reference) ?: return null
            return if (body[0].opcode == Opcode.SGET_OBJECT && body[1].opcode == Opcode.INVOKE_DIRECT &&
                body[2].opcode == Opcode.RETURN_VOID && core.definingClass == navigatorType
            ) operation to core else null
        }
        val replaceMethod = navigator.methods.single { it.name == replaceCommit.name && it.parameterTypes.isEmpty() }
        val (replaceOperation, coreRef) = replaceMethod.commitOperation()
            ?: throw PatchException("Story viewer commit is not a navigator operation")
        val core = navigator.methods.single { it.name == coreRef.name && it.params == coreRef.parameterTypes.map { p -> p.toString() } }
        val coreBody = core.implementation!!.instructions.toList()
        fun addsFragment(operation: FieldReference) = coreBody.withIndex().any { (index, instruction) ->
            instruction.opcode == Opcode.SGET_OBJECT && (instruction as ReferenceInstruction).reference.toString() == operation.toString() &&
                coreBody.drop(index + 1).firstOrNull { it.opcode == Opcode.INVOKE_VIRTUAL }?.let { call ->
                    val ref = methodRef((call as ReferenceInstruction).reference)!!
                    val body = classDefBy(ref.definingClass).methods.singleOrNull {
                        it.name == ref.name && it.params == ref.parameterTypes.map { p -> p.toString() }
                    }?.implementation?.instructions?.toList() ?: return@let false
                    body.size >= 2 && body[0].opcode == Opcode.CONST_4 &&
                        (body[0] as NarrowLiteralInstruction).narrowLiteral == 1 &&
                        methodRef((body[1] as? ReferenceInstruction)?.reference)?.parameterTypes?.size == 4
                } == true
        }
        if (addsFragment(replaceOperation)) throw PatchException("Story viewer commit already adds")
        val addMethod = navigator.methods.singleOrNull { method ->
            method.commitOperation()?.let { (operation, ref) -> ref == coreRef && addsFragment(operation) } == true
        } ?: throw PatchException("Navigator has no add commit")
        if (!AccessFlags.PUBLIC.isSet(addMethod.accessFlags)) throw PatchException("Navigator add commit is not public")

        mutableClassDefBy(launcherType).apply {
            addBridge(
                "feurstagramCommitStoryViewer", listOf("Ljava/lang/Object;"), "V", registers = 2,
                """
                    invoke-static { }, $QUOICOUVIEW->stacksStoryViewer()Z
                    move-result v0
                    check-cast p0, $navigatorType
                    if-eqz v0, :replace
                    invoke-virtual { p0 }, $navigatorType->${addMethod.name}()V
                    invoke-static { }, $QUOICOUVIEW->trayControllerToCover()Ljava/lang/Object;
                    move-result-object v0
                    if-eqz v0, :covered
                    check-cast v0, $controllerType
                    invoke-virtual { v0 }, $controllerType->onPause()V
                    :covered
                    return-void
                    :replace
                    invoke-virtual { p0 }, $navigatorType->${replaceCommit.name}()V
                    return-void
                """,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            )
        }
        val navigatorRegister = (commit as FiveRegisterInstruction).registerC
        launcher.replaceInstruction(
            commit.location.index,
            "invoke-static { v$navigatorRegister }, $launcherType->feurstagramCommitStoryViewer(Ljava/lang/Object;)V",
        )
        // Which launch this is, from its parameters (the second argument),
        // before the method reuses that register.
        launcher.addInstructions(
            0,
            "invoke-static/range { p1 .. p1 }, $QUOICOUVIEW->onStoryViewerLaunch(Ljava/lang/Object;)V",
        )

        // The viewer itself: which one was stacked, and when it goes.
        mutableClassDefBy(STORY_VIEWER).apply {
            val create = methods.singleOrNull { it.name == "onCreate" && it.params == listOf("Landroid/os/Bundle;") }
                ?: throw PatchException("Story viewer has no onCreate")
            create.addInstructions(0, "invoke-static/range { p0 .. p0 }, $QUOICOUVIEW->onStoryViewerCreated(Ljava/lang/Object;)V")
            val destroy = methods.singleOrNull { it.name == "onDestroy" && it.parameterTypes.isEmpty() }
                ?: throw PatchException("Story viewer has no onDestroy")
            // At the end: by then the viewer has handed the tray its closing animation.
            destroy.instructions.filter { it.opcode == Opcode.RETURN_VOID }.reversed().forEach { ret ->
                destroy.addInstructionsWithLabels(
                    ret.location.index,
                    """
                        invoke-static/range { p0 .. p0 }, $QUOICOUVIEW->trayControllerToUncover(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object v0
                        if-eqz v0, :done
                        check-cast v0, $controllerType
                        invoke-virtual { v0 }, $controllerType->onResume()V
                    """,
                    ExternalLabel("done", ret),
                )
            }
        }
    }
}

/** Abstract methods of an interface and of the interfaces it extends. */
private fun app.morphe.patcher.patch.BytecodePatchContext.abstractMethods(type: String): List<Method> {
    val classDef: ClassDef = classDefBy(type)
    return classDef.methods.filter { AccessFlags.ABSTRACT.isSet(it.accessFlags) } +
        classDef.interfaces.flatMap { abstractMethods(it) }
}

/** Add a method (public by default) with the given smali body to an extension class. */
private fun MutableClass.addBridge(
    name: String,
    parameters: List<String>,
    returnType: String,
    registers: Int,
    smali: String,
    accessFlags: Int = AccessFlags.PUBLIC.value,
) {
    methods.add(
        ImmutableMethod(
            type,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            accessFlags,
            null,
            null,
            MutableMethodImplementation(registers),
        ).toMutable().apply { addInstructions(0, smali.trimIndent()) },
    )
}
