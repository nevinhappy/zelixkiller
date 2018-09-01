package me.nov.zelixkiller.transformer.zkm11;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;

import me.lpk.analysis.Sandbox.ClassDefiner;
import me.nov.zelixkiller.JarArchive;
import me.nov.zelixkiller.ZelixKiller;
import me.nov.zelixkiller.transformer.Transformer;
import me.nov.zelixkiller.transformer.zkm11.utils.ClinitCutter;
import me.nov.zelixkiller.utils.ClassUtils;
import me.nov.zelixkiller.utils.MethodUtils;
import me.nov.zelixkiller.utils.analysis.ConstantTracker;
import me.nov.zelixkiller.utils.analysis.ConstantTracker.ConstantValue;
import sun.misc.Unsafe;

/**
 * Decrypts ZKM String Obfuscation technique that uses DES Creates a VM and deobfuscates by invoking static initializer
 */
@SuppressWarnings("restriction")
public class StringObfuscationCipherVMT11 extends Transformer {

	public int success = 0;
	public int failures = 0;

	@Override
	public boolean isAffected(ClassNode cn) {
		return false;
	}

	@Override
	public void transform(JarArchive ja, ClassNode cn) {
	}

	@Override
	public void preTransform(JarArchive ja) {
		ClassDefiner vm = new ClassDefiner(ClassLoader.getSystemClassLoader());
		ArrayList<ClassNode> dc = findDecryptionClasses(ja.getClasses());
		for (ClassNode cn : dc) {
			ClassWriter cw2 = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(cw2);
			vm.predefine(cn.name.replace("/", "."), cw2.toByteArray());
		}
		for (ClassNode cn : ja.getClasses().values()) {
			if (dc.contains(cn))
				continue;
			ClassNode proxy = createProxy(cn);
			ClassWriter cw2 = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			proxy.accept(cw2);
			vm.predefine(proxy.name.replace("/", "."), cw2.toByteArray());
		}
		Unsafe unsafe = getUnsafe();
		for (ClassNode cn : ja.getClasses().values()) {
			try {
				Class<?> clazz = vm.findClass(cn.name.replace("/", "."));
				unsafe.ensureClassInitialized(clazz);
				replaceInvokedynamicCalls(clazz, cn);
				success++;
			} catch (Throwable t) {
				ZelixKiller.logger.log(Level.SEVERE, "Exception at loading proxy " + cn.name, t);
				failures++;
			}
		}
	}

	private static Unsafe getUnsafe() {
		try {

			Field singleoneInstanceField = Unsafe.class.getDeclaredField("theUnsafe");
			singleoneInstanceField.setAccessible(true);
			return (Unsafe) singleoneInstanceField.get(null);
		} catch (Throwable e) {
			return null;
		}
	}

	private ArrayList<ClassNode> findDecryptionClasses(Map<String, ClassNode> map) {
		ArrayList<ClassNode> dc = new ArrayList<>();
		Outer: for (ClassNode cn : map.values()) {
			MethodNode clinit = cn.methods.stream().filter(mn -> mn.name.equals("<clinit>")).findFirst().orElse(null);
			if (clinit != null && StringObfuscationCipherT11.containsDESPadLDC(clinit)) {
				for (AbstractInsnNode ain : clinit.instructions.toArray()) {
					if (ain instanceof MethodInsnNode) {
						MethodInsnNode min = (MethodInsnNode) ain;
						if (min.desc.startsWith("(JJLjava/lang/Object;)L")) {
							for (MethodNode mn : map.get(min.owner).methods) {
								for (ClassNode decl : findBelongingClasses(new ArrayList<>(), mn, map)) {
									if (!dc.contains(decl))
										dc.add(decl);
								}
							}
							break Outer;
						}
					}
				}
			}
		}
		return dc;
	}

	private Collection<ClassNode> findBelongingClasses(ArrayList<MethodNode> visited, MethodNode method,
			Map<String, ClassNode> map) {
		ArrayList<ClassNode> list = new ArrayList<>();
		if (visited.contains(method))
			return list;
		visited.add(method);
		for (AbstractInsnNode ain : method.instructions.toArray()) {
			if (ain instanceof MethodInsnNode) {
				MethodInsnNode min = (MethodInsnNode) ain;
				if (!min.owner.startsWith("java/") && !min.owner.startsWith("javax/")) {
					ClassNode decryptionClass = map.get(min.owner);
					if (decryptionClass != null && !list.contains(decryptionClass)) {
						list.add(decryptionClass);
						for (MethodNode mn : decryptionClass.methods) {
							for (ClassNode cn : findBelongingClasses(visited, mn, map)) {
								if (!list.contains(cn)) {
									list.add(cn);
								}
							}
						}
					}
				}

			}
		}
		return list;
	}

	@SuppressWarnings("deprecation")
	private ClassNode createProxy(ClassNode cn) {
		ClassNode proxy = new ClassNode();
		proxy.name = cn.name;
		proxy.version = cn.version;
		proxy.superName = cn.superName;
		proxy.interfaces = cn.interfaces;
		proxy.access = cn.access;
		MethodNode clinit = cn.methods.stream().filter(mn -> mn.name.equals("<clinit>")).findFirst().orElse(null);
		if (clinit != null && StringObfuscationCipherT11.containsDESPadLDC(clinit)) {
			try {
				InsnList decryption = ClinitCutter.cutClinit(clinit.instructions);
				MethodNode newclinit = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
				newclinit.instructions.add(decryption);
				newclinit.maxStack = 10;
				newclinit.maxLocals = 20;
				proxy.methods.add(newclinit);
				ArrayList<String> neededClassContents = findNeededContents(cn, newclinit);
				for (FieldNode fn : cn.fields) {
					if (neededClassContents.contains(fn.name + fn.desc))
						proxy.fields.add(new FieldNode(fn.access, fn.name, fn.desc, fn.signature, fn.value));
				}
				for (MethodNode mn : cn.methods) {
					if (neededClassContents.contains(mn.name + mn.desc)) {
						proxy.methods.add(MethodUtils.cloneInstructions(mn));
					}
				}
			} catch (Exception e) {
				e.printStackTrace();

			}
		}
		return proxy;
	}

	private ArrayList<String> findNeededContents(ClassNode cn, MethodNode mn) {
		ArrayList<String> neededContents = new ArrayList<>();
		for (AbstractInsnNode ain : mn.instructions.toArray()) {
			if (ain instanceof MethodInsnNode) {
				MethodInsnNode min = (MethodInsnNode) ain;
				if (min.owner.equals(cn.name) && !neededContents.contains(min.name + min.desc)) {
					neededContents.add(min.name + min.desc);
					neededContents.addAll(findNeededContents(cn, ClassUtils.getMethod(cn, min.name, min.desc)));
				}
			}
			if (ain instanceof FieldInsnNode) {
				FieldInsnNode fin = (FieldInsnNode) ain;
				if (fin.owner.equals(cn.name) && !neededContents.contains(fin.name + fin.desc)) {
					neededContents.add(fin.name + fin.desc);
				}
			}
		}
		return neededContents;
	}

	private void replaceInvokedynamicCalls(Class<?> proxy, ClassNode cn) {
		for (MethodNode mn : cn.methods) {
			try {
				HashMap<AbstractInsnNode, String> decryptedStringMap = new HashMap<>();
				int nIdx = 0;
				for (AbstractInsnNode ain : mn.instructions.toArray()) {
					if (ain.getOpcode() == GETSTATIC) {
						FieldInsnNode fin = (FieldInsnNode) ain;
						if (fin.owner.equals(cn.name) && fin.desc.equals("J")) {
							// inline needed fields
							try {
								Field f = proxy.getDeclaredField(fin.name);
								if (f != null && f.getType() == long.class) {
									f.setAccessible(true);
									mn.instructions.set(fin, new LdcInsnNode((long) f.get(null)));
								}
							} catch (Exception e) {
								ZelixKiller.logger.log(Level.SEVERE, "Exception at inlining field", e);
								continue;
							}
						}
					} else if (ain.getOpcode() == INVOKEDYNAMIC) {
						// invokedynamic just invokes (String, long, int) method
						InvokeDynamicInsnNode idyn = (InvokeDynamicInsnNode) ain;
						if (idyn.desc.equals("(IJ)Ljava/lang/String;") && idyn.bsm != null && idyn.bsm.getOwner().equals(cn.name)
								&& idyn.bsm.getDesc().equals(
										"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;")) {
							Analyzer<ConstantValue> a = new Analyzer<>(new ConstantTracker());
							a.analyze(cn.name, mn);
							Frame<ConstantValue>[] frames = a.getFrames();
							Frame<ConstantValue> frame = frames[nIdx];
							int j = 0;
							Object[] args = new Object[2];
							for (int i = frame.getStackSize() - 1; i > frame.getStackSize() - 3; i--) {
								ConstantValue v = frame.getStack(i);
								if (v != null)
									args[j++] = v.getValue();
							}
							for (Method m : proxy.getDeclaredMethods()) {
								if (m.getReturnType() == String.class && m.getParameterTypes()[0] == int.class
										&& m.getParameterTypes()[1] == long.class) {
									try {
										m.setAccessible(true);
										String decrypted = (String) m.invoke(null, args[1], args[0]);
										decryptedStringMap.put(idyn, decrypted);
									} catch (Exception e) {
										throw new RuntimeException("math method threw exception", e);
									}
									break;
								}
							}
						}
					}
					nIdx++;
				}
				for (Entry<AbstractInsnNode, String> entry : decryptedStringMap.entrySet()) {
					mn.instructions.insertBefore(entry.getKey(), new InsnNode(POP2));
					mn.instructions.insertBefore(entry.getKey(), new InsnNode(POP));
					mn.instructions.set(entry.getKey(), new LdcInsnNode(entry.getValue()));
				}
			} catch (AnalyzerException e) {
				e.printStackTrace();
				continue;
			}
		}
	}

	@Override
	public void postTransform() {
		ZelixKiller.logger.log(Level.INFO, "Succeeded in " + success + " classes, failed in " + failures);
	}
}