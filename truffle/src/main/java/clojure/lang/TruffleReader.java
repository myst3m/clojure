/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

package clojure.lang;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.lang.Character;
import java.lang.Class;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.IllegalStateException;
import java.lang.Integer;
import java.lang.Number;
import java.lang.NumberFormatException;
import java.lang.Object;
import java.lang.RuntimeException;
import java.lang.String;
import java.lang.StringBuilder;
import java.lang.Throwable;
import java.lang.UnsupportedOperationException;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TruffleReader{

static final Symbol QUOTE = Symbol.intern("quote");
static final Symbol THE_VAR = Symbol.intern("var");
//static Symbol SYNTAX_QUOTE = Symbol.intern(null, "syntax-quote");
static Symbol UNQUOTE = Symbol.intern("clojure.core", "unquote");
static Symbol UNQUOTE_SPLICING = Symbol.intern("clojure.core", "unquote-splicing");
static Symbol CONCAT = Symbol.intern("clojure.core", "concat");
static Symbol SEQ = Symbol.intern("clojure.core", "seq");
static Symbol LIST = Symbol.intern("clojure.core", "list");
static Symbol APPLY = Symbol.intern("clojure.core", "apply");
static Symbol HASHMAP = Symbol.intern("clojure.core", "hash-map");
static Symbol HASHSET = Symbol.intern("clojure.core", "hash-set");
static Symbol VECTOR = Symbol.intern("clojure.core", "vector");
static Symbol WITH_META = Symbol.intern("clojure.core", "with-meta");
static Symbol META = Symbol.intern("clojure.core", "meta");
static Symbol DEREF = Symbol.intern("clojure.core", "deref");
static Symbol READ_COND = Symbol.intern("clojure.core", "read-cond");
static Symbol READ_COND_SPLICING = Symbol.intern("clojure.core", "read-cond-splicing");
static Keyword UNKNOWN = Keyword.intern(null, "unknown");
//static Symbol DEREF_BANG = Symbol.intern("clojure.core", "deref!");

static IFn[] macros = new IFn[256];
static IFn[] dispatchMacros = new IFn[256];
//static Pattern symbolPat = Pattern.compile("[:]?([\\D&&[^:/]][^:/]*/)?[\\D&&[^:/]][^:/]*");
static Pattern symbolPat = Pattern.compile("[:]?([\\D&&[^/]].*/)?(/|[\\D&&[^/]][^/]*)");
static Pattern arraySymbolPat = Pattern.compile("([\\D&&[^/:]].*)/([1-9])");
//static Pattern varPat = Pattern.compile("([\\D&&[^:\\.]][^:\\.]*):([\\D&&[^:\\.]][^:\\.]*)");
//static Pattern intPat = Pattern.compile("[-+]?[0-9]+\\.?");
static Pattern intPat =
		Pattern.compile(
				"([-+]?)(?:(0)|([1-9][0-9]*)|0[xX]([0-9A-Fa-f]+)|0([0-7]+)|([1-9][0-9]?)[rR]([0-9A-Za-z]+)|0[0-9]+)(N)?");
static Pattern ratioPat = Pattern.compile("([-+]?[0-9]+)/([0-9]+)");
static Pattern floatPat = Pattern.compile("([-+]?[0-9]+(\\.[0-9]*)?([eE][-+]?[0-9]+)?)(M)?");
static Pattern argPat = Pattern.compile("%(?:(&)|([1-9][0-9]*))?");
//static Pattern accessorPat = Pattern.compile("\\.[a-zA-Z_]\\w*");
//static Pattern instanceMemberPat = Pattern.compile("\\.([a-zA-Z_][\\w\\.]*)\\.([a-zA-Z_]\\w*)");
//static Pattern staticMemberPat = Pattern.compile("([a-zA-Z_][\\w\\.]*)\\.([a-zA-Z_]\\w*)");
//static Pattern classNamePat = Pattern.compile("([a-zA-Z_][\\w\\.]*)\\.");

//symbol->gensymbol
static Var GENSYM_ENV = Var.create(null).setDynamic();
//sorted-map num->gensymbol
static Var ARG_ENV = Var.create(null).setDynamic();
static IFn ctorReader = new CtorReader();

// Dynamic var set to true in a read-cond context
static Var READ_COND_ENV = Var.create(null).setDynamic();

static
	{
	macros['"'] = new StringReader();
	macros[';'] = new CommentReader();
	macros['\''] = new WrappingReader(QUOTE);
	macros['@'] = new WrappingReader(DEREF);//new DerefReader();
	macros['^'] = new MetaReader();
	macros['`'] = new SyntaxQuoteReader();
	macros['~'] = new UnquoteReader();
	macros['('] = new ListReader();
	macros[')'] = new UnmatchedDelimiterReader();
	macros['['] = new VectorReader();
	macros[']'] = new UnmatchedDelimiterReader();
	macros['{'] = new MapReader();
	macros['}'] = new UnmatchedDelimiterReader();
//	macros['|'] = new ArgVectorReader();
	macros['\\'] = new CharacterReader();
	macros['%'] = new ArgReader();
	macros['#'] = new DispatchReader();


	dispatchMacros['^'] = new MetaReader();
	dispatchMacros['#'] = new SymbolicValueReader();
	dispatchMacros['\''] = new VarReader();
	dispatchMacros['"'] = new RegexReader();
	dispatchMacros['('] = new FnReader();
	dispatchMacros['{'] = new SetReader();
	dispatchMacros['='] = new EvalReader();
	dispatchMacros['!'] = new CommentReader();
	dispatchMacros['<'] = new UnreadableReader();
	dispatchMacros['_'] = new DiscardReader();
	dispatchMacros['?'] = new ConditionalReader();
	dispatchMacros[':'] = new NamespaceMapReader();
	}

// ======== Static fields formerly from RT ========
static final public Boolean T = Boolean.TRUE;
static final public Boolean F = Boolean.FALSE;
static final public Object[] EMPTY_ARRAY = new Object[]{};
static final Namespace CLOJURE_NS = Namespace.findOrCreate(Symbol.intern("clojure.core"));
static final Keyword TAG_KEY = Keyword.intern(null, "tag");
static final Keyword PARAM_TAGS_KEY = Keyword.intern(null, "param-tags");
static Keyword LINE_KEY = Keyword.intern(null, "line");
static Keyword COLUMN_KEY = Keyword.intern(null, "column");

static Object readeval = readTrueFalseUnknown(System.getProperty("clojure.read.eval","true"));
static Object readTrueFalseUnknown(String s){
	if(s.equals("true")) return Boolean.TRUE;
	else if(s.equals("false")) return Boolean.FALSE;
	return Keyword.intern(null, "unknown");
}

final static public Var READEVAL = Var.intern(CLOJURE_NS, Symbol.intern("*read-eval*"), readeval).setDynamic();
final static public Var DATA_READERS = Var.intern(CLOJURE_NS, Symbol.intern("*data-readers*"), PersistentArrayMap.EMPTY).setDynamic();
final static public Var DEFAULT_DATA_READER_FN = Var.intern(CLOJURE_NS, Symbol.intern("*default-data-reader-fn*"), PersistentArrayMap.EMPTY).setDynamic();
final static public Var DEFAULT_DATA_READERS = Var.intern(CLOJURE_NS, Symbol.intern("default-data-readers"), PersistentArrayMap.EMPTY);
final static public Var SUPPRESS_READ = Var.intern(CLOJURE_NS, Symbol.intern("*suppress-read*"), null).setDynamic();
public final static Var READER_RESOLVER = Var.intern(CLOJURE_NS, Symbol.intern("*reader-resolver*"), null).setDynamic();

/**
 * Hook for evaluating #= read-eval forms via the Truffle runtime.
 * Set by ClojureContext during initialization.
 * Signature: (IPersistentList form) -> Object
 */
public static volatile java.util.function.Function<IPersistentList, Object> readEvalHook;

static java.util.concurrent.atomic.AtomicInteger id = new java.util.concurrent.atomic.AtomicInteger(1);

// ======== Helper methods formerly from RT ========

static public int nextID(){
	return id.getAndIncrement();
}

static public ISeq seq(Object coll){
	if(coll instanceof ASeq) return (ASeq) coll;
	else if(coll instanceof LazySeq) return ((LazySeq) coll).seq();
	else return seqFrom(coll);
}

static ISeq seqFrom(Object coll){
	if(coll instanceof Seqable) return ((Seqable) coll).seq();
	else if(coll == null) return null;
	else if(coll instanceof Iterable) return IteratorSeq.create(((Iterable) coll).iterator());
	else if(coll.getClass().isArray()) {
		int len = java.lang.reflect.Array.getLength(coll);
		Object[] arr = new Object[len];
		for(int i = 0; i < len; i++) arr[i] = java.lang.reflect.Array.get(coll, i);
		return PersistentList.create(java.util.Arrays.asList(arr)).seq();
	}
	else if(coll instanceof CharSequence) return StringSeq.create((CharSequence) coll);
	else if(coll instanceof Map) return seq(((Map) coll).entrySet());
	else throw new IllegalArgumentException("Don't know how to create ISeq from: " + coll.getClass().getName());
}

static public Object first(Object x){
	if(x instanceof ISeq) return ((ISeq) x).first();
	ISeq s = seq(x);
	if(s == null) return null;
	return s.first();
}

static public Object second(Object x){
	return first(next(x));
}

static public ISeq next(Object x){
	if(x instanceof ISeq) return ((ISeq) x).next();
	ISeq s = seq(x);
	if(s == null) return null;
	return s.next();
}

static public ISeq cons(Object x, Object coll){
	if(coll == null) return new PersistentList(x);
	else if(coll instanceof ISeq) return new Cons(x, (ISeq) coll);
	else return new Cons(x, seq(coll));
}

static public IPersistentCollection conj(IPersistentCollection coll, Object x){
	if(coll == null) return new PersistentList(x);
	return coll.cons(x);
}

static public IPersistentMap meta(Object x){
	if(x instanceof IMeta) return ((IMeta) x).meta();
	return null;
}

static public Object get(Object coll, Object key){
	if(coll instanceof ILookup) return ((ILookup) coll).valAt(key);
	if(coll == null) return null;
	if(coll instanceof Map) return ((Map) coll).get(key);
	if(coll instanceof IPersistentSet) return ((IPersistentSet) coll).get(key);
	if(coll instanceof ITransientSet) return ((ITransientSet) coll).get(key);
	return null;
}

static public Object get(Object coll, Object key, Object notFound){
	if(coll instanceof ILookup) return ((ILookup) coll).valAt(key, notFound);
	if(coll == null) return notFound;
	if(coll instanceof Map){
		Map m = (Map) coll;
		return m.containsKey(key) ? m.get(key) : notFound;
	}
	if(coll instanceof IPersistentSet){
		IPersistentSet s = (IPersistentSet) coll;
		return s.contains(key) ? s.get(key) : notFound;
	}
	return notFound;
}

static public Associative assoc(Object coll, Object key, Object val){
	if(coll == null) return new PersistentArrayMap(new Object[]{key, val});
	return ((Associative) coll).assoc(key, val);
}

static public IPersistentMap map(Object... init){
	if(init == null || init.length == 0) return PersistentArrayMap.EMPTY;
	else if(init.length <= 16) return PersistentArrayMap.createWithCheck(init);
	return PersistentHashMap.createWithCheck(init);
}

static public IPersistentMap mapUniqueKeys(Object... init){
	if(init == null) return PersistentArrayMap.EMPTY;
	else if(init.length <= 16) return new PersistentArrayMap(init);
	return PersistentHashMap.create(init);
}

static public IPersistentSet set(Object... init){
	return PersistentHashSet.createWithCheck(init);
}

static public ISeq list(){
	return null;
}
static public ISeq list(Object arg1){
	return new PersistentList(arg1);
}
static public ISeq list(Object arg1, Object arg2){
	return listStar(arg1, arg2, null);
}
static public ISeq list(Object arg1, Object arg2, Object arg3){
	return listStar(arg1, arg2, arg3, null);
}
static public ISeq list(Object arg1, Object arg2, Object arg3, Object arg4){
	return listStar(arg1, arg2, arg3, arg4, null);
}
static public ISeq list(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5){
	return listStar(arg1, arg2, arg3, arg4, arg5, null);
}

static public ISeq listStar(Object arg1, ISeq rest){
	return (ISeq) cons(arg1, rest);
}
static public ISeq listStar(Object arg1, Object arg2, ISeq rest){
	return (ISeq) cons(arg1, cons(arg2, rest));
}
static public ISeq listStar(Object arg1, Object arg2, Object arg3, ISeq rest){
	return (ISeq) cons(arg1, cons(arg2, cons(arg3, rest)));
}
static public ISeq listStar(Object arg1, Object arg2, Object arg3, Object arg4, ISeq rest){
	return (ISeq) cons(arg1, cons(arg2, cons(arg3, cons(arg4, rest))));
}
static public ISeq listStar(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, ISeq rest){
	return (ISeq) cons(arg1, cons(arg2, cons(arg3, cons(arg4, cons(arg5, rest)))));
}

static public ISeq keys(Object coll){
	if(coll instanceof IPersistentMap)
		return APersistentMap.KeySeq.createFromMap((IPersistentMap) coll);
	else
		return APersistentMap.KeySeq.create(seq(coll));
}

static public Object[] toArray(Object coll){
	if(coll == null) return EMPTY_ARRAY;
	else if(coll instanceof Object[]) return (Object[]) coll;
	else if(coll instanceof java.util.Collection) return ((java.util.Collection) coll).toArray();
	else if(coll instanceof Iterable){
		ArrayList ret = new ArrayList();
		for(Object o : (Iterable)coll) ret.add(o);
		return ret.toArray();
	}
	else if(coll instanceof Map) return ((Map) coll).entrySet().toArray();
	else {
		throw new IllegalArgumentException("Don't know how to create array from: " + coll.getClass().getName());
	}
}

static public boolean booleanCast(Object x){
	if(x instanceof Boolean) return ((Boolean) x).booleanValue();
	return x != null;
}

static public boolean suppressRead(){
	return booleanCast(SUPPRESS_READ.deref());
}

@SuppressWarnings("unchecked")
static public Class classForName(String name){
	try {
		return Class.forName(name, true, Thread.currentThread().getContextClassLoader());
	} catch(ClassNotFoundException e){
		throw Util.sneakyThrow(e);
	}
}

@SuppressWarnings("unchecked")
static public Class classForNameNonLoading(String name){
	try {
		return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
	} catch(ClassNotFoundException e){
		throw Util.sneakyThrow(e);
	}
}

static public Var var(String ns, String name){
	return Var.intern(Namespace.findOrCreate(Symbol.intern(null, ns)), Symbol.intern(null, name));
}

// ======== End helper methods ========

public static interface Resolver{
    Symbol currentNS();
    Symbol resolveClass(Symbol sym);
    Symbol resolveAlias(Symbol sym);
    Symbol resolveVar(Symbol sym);
}

static boolean isWhitespace(int ch){
	return Character.isWhitespace(ch) || ch == ',';
}

static void unread(PushbackReader r, int ch) {
	if(ch != -1)
		try
			{
			r.unread(ch);
			}
		catch(IOException e)
			{
			throw Util.sneakyThrow(e);
			}
}

public static class ReaderException extends RuntimeException implements IExceptionInfo{
	public final int line;
	public final int column;
	public final Object data;

    final static public String ERR_NS = "clojure.error";
	final static public Keyword ERR_LINE = Keyword.intern(ERR_NS, "line");
	final static public Keyword ERR_COLUMN = Keyword.intern(ERR_NS, "column");

	public ReaderException(int line, int column, Throwable cause){
		super(cause);
		this.line = line;
		this.column = column;
		this.data = TruffleReader.map(ERR_LINE, line, ERR_COLUMN, column);
	}

	public IPersistentMap getData(){
		return (IPersistentMap)data;
	}
}

static public int read1(Reader r){
	try
		{
		return r.read();
		}
	catch(IOException e)
		{
		throw Util.sneakyThrow(e);
		}
}

// Reader opts
static public final Keyword OPT_EOF = Keyword.intern(null,"eof");
static public final Keyword OPT_FEATURES = Keyword.intern(null,"features");
static public final Keyword OPT_READ_COND = Keyword.intern(null, "read-cond");

// EOF special value to throw on eof
static public final Keyword EOFTHROW = Keyword.intern(null,"eofthrow");

// Platform features - always installed
static private final Keyword PLATFORM_KEY = Keyword.intern(null, "clj");
static private final Object PLATFORM_FEATURES = PersistentHashSet.create(PLATFORM_KEY);

// Reader conditional options - use with :read-cond
static public final Keyword COND_ALLOW = Keyword.intern(null, "allow");
    static public final Keyword COND_PRESERVE = Keyword.intern(null, "preserve");

static public Object read(PushbackReader r, Object opts){
    boolean eofIsError = true;
    Object eofValue = null;
    if(opts != null && opts instanceof IPersistentMap)
    {
        Object eof = ((IPersistentMap)opts).valAt(OPT_EOF, EOFTHROW);
        if(!EOFTHROW.equals(eof)) {
            eofIsError = false;
            eofValue = eof;
        }
    }
    return read(r,eofIsError,eofValue,false,opts);
}

static public Object read(PushbackReader r, boolean eofIsError, Object eofValue, boolean isRecursive)
{
    return read(r, eofIsError, eofValue, isRecursive, PersistentHashMap.EMPTY);
}

static public Object read(PushbackReader r, boolean eofIsError, Object eofValue, boolean isRecursive, Object opts)
{
	// start with pendingForms null as reader conditional splicing is not allowed at top level
	return read(r, eofIsError, eofValue, null, null, isRecursive, opts, null, (Resolver) TruffleReader.READER_RESOLVER.deref());
}

static private Object read(PushbackReader r, boolean eofIsError, Object eofValue, boolean isRecursive, Object opts, Object pendingForms) {
	return read(r, eofIsError, eofValue, null, null, isRecursive, opts, ensurePending(pendingForms), (Resolver) TruffleReader.READER_RESOLVER.deref());
}

static private Object ensurePending(Object pendingForms) {
	if(pendingForms == null)
		return new LinkedList();
	else
		return pendingForms;
}

static private Object installPlatformFeature(Object opts) {
    if(opts == null)
        return TruffleReader.mapUniqueKeys(OPT_FEATURES, PLATFORM_FEATURES);
    else {
        IPersistentMap mopts = (IPersistentMap) opts;
        Object features = mopts.valAt(OPT_FEATURES);
        if (features == null)
            return mopts.assoc(OPT_FEATURES, PLATFORM_FEATURES);
        else
            return mopts.assoc(OPT_FEATURES, TruffleReader.conj((IPersistentSet) features, PLATFORM_KEY));
    }
}

static private Object read(PushbackReader r, boolean eofIsError, Object eofValue, Character returnOn,
                           Object returnOnValue, boolean isRecursive, Object opts, Object pendingForms,
                           Resolver resolver)
{
    if(TruffleReader.READEVAL.deref() == UNKNOWN)
        throw Util.runtimeException("Reading disallowed - *read-eval* bound to :unknown");

    opts = installPlatformFeature(opts);

	try
		{
		for(; ;)
			{

			if(pendingForms instanceof List && !((List)pendingForms).isEmpty())
				return ((List)pendingForms).remove(0);

			int ch = read1(r);

			while(isWhitespace(ch))
				ch = read1(r);

			if(ch == -1)
				{
				if(eofIsError)
					throw Util.runtimeException("EOF while reading");
				return eofValue;
				}

			if(returnOn != null && (returnOn.charValue() == ch)) {
				return returnOnValue;
			}

			if(Character.isDigit(ch))
				{
				Object n = readNumber(r, (char) ch);
				return n;
				}

			IFn macroFn = getMacro(ch);
			if(macroFn != null)
				{
				Object ret = macroFn.invoke(r, (char) ch, opts, pendingForms);
				//no op macros return the reader
				if(ret == r)
					continue;
				return ret;
				}

			if(ch == '+' || ch == '-')
				{
				int ch2 = read1(r);
				if(Character.isDigit(ch2))
					{
					unread(r, ch2);
					Object n = readNumber(r, (char) ch);
					return n;
					}
				unread(r, ch2);
				}

			String token = readToken(r, (char) ch);
			return interpretToken(token, resolver);
			}
		}
	catch(Exception e)
		{
		if(isRecursive || !(r instanceof LineNumberingPushbackReader))
			throw Util.sneakyThrow(e);
		LineNumberingPushbackReader rdr = (LineNumberingPushbackReader) r;
		//throw Util.runtimeException(String.format("ReaderError:(%d,1) %s", rdr.getLineNumber(), e.getMessage()), e);
		throw new ReaderException(rdr.getLineNumber(), rdr.getColumnNumber(), e);
		}
}

static private String readToken(PushbackReader r, char initch) {
	StringBuilder sb = new StringBuilder();
	sb.append(initch);

	for(; ;)
		{
		int ch = read1(r);
		if(ch == -1 || isWhitespace(ch) || isTerminatingMacro(ch))
			{
			unread(r, ch);
			return sb.toString();
			}
		sb.append((char) ch);
		}
}

static private Object readNumber(PushbackReader r, char initch) {
	StringBuilder sb = new StringBuilder();
	sb.append(initch);

	for(; ;)
		{
		int ch = read1(r);
		if(ch == -1 || isWhitespace(ch) || isMacro(ch))
			{
			unread(r, ch);
			break;
			}
		sb.append((char) ch);
		}

	String s = sb.toString();
	Object n = matchNumber(s);
	if(n == null)
		throw new NumberFormatException("Invalid number: " + s);
	return n;
}

static private int readUnicodeChar(String token, int offset, int length, int base) {
	if(token.length() != offset + length)
		throw new IllegalArgumentException("Invalid unicode character: \\" + token);
	int uc = 0;
	for(int i = offset; i < offset + length; ++i)
		{
		int d = Character.digit(token.charAt(i), base);
		if(d == -1)
			throw new IllegalArgumentException("Invalid digit: " + token.charAt(i));
		uc = uc * base + d;
		}
	return (char) uc;
}

static private int readUnicodeChar(PushbackReader r, int initch, int base, int length, boolean exact) {
	int uc = Character.digit(initch, base);
	if(uc == -1)
		throw new IllegalArgumentException("Invalid digit: " + (char) initch);
	int i = 1;
	for(; i < length; ++i)
		{
		int ch = read1(r);
		if(ch == -1 || isWhitespace(ch) || isMacro(ch))
			{
			unread(r, ch);
			break;
			}
		int d = Character.digit(ch, base);
		if(d == -1)
			throw new IllegalArgumentException("Invalid digit: " + (char) ch);
		uc = uc * base + d;
		}
	if(i != length && exact)
		throw new IllegalArgumentException("Invalid character length: " + i + ", should be: " + length);
	return uc;
}

static private Object interpretToken(String s, Resolver resolver) {
	if(s.equals("nil"))
		{
		return null;
		}
	else if(s.equals("true"))
		{
		return TruffleReader.T;
		}
	else if(s.equals("false"))
		{
		return TruffleReader.F;
		}
	Object ret = null;

	ret = matchSymbol(s, resolver);
	if(ret != null)
		return ret;

	throw Util.runtimeException("Invalid token: " + s);
}


private static Object matchSymbol(String s, Resolver resolver){
	Matcher m = symbolPat.matcher(s);
	if(m.matches())
		{
		int gc = m.groupCount();
		String ns = m.group(1);
		String name = m.group(2);
		if(ns != null && ns.endsWith(":/")
		   || name.endsWith(":")
		   || s.indexOf("::", 1) != -1)
			return null;
		if(s.startsWith("::"))
			{
			Symbol ks = Symbol.intern(s.substring(2));
            if(resolver != null)
                {
                Symbol nsym;
                if(ks.ns != null)
                    nsym = resolver.resolveAlias(Symbol.intern(ks.ns));
                else
                    nsym = resolver.currentNS();
                //auto-resolving keyword
                if(nsym != null)
                    return Keyword.intern(nsym.name, ks.name);
                else
                    return null;
                }
            else
                {
                Namespace kns;
                if(ks.ns != null)
                    kns = Compiler.currentNS().lookupAlias(Symbol.intern(ks.ns));
                else
                    kns = Compiler.currentNS();
                //auto-resolving keyword
                if(kns != null)
                    return Keyword.intern(kns.name.name, ks.name);
                else
                    return null;
                }
            }
		boolean isKeyword = s.charAt(0) == ':';
		Symbol sym = Symbol.intern(s.substring(isKeyword ? 1 : 0));
		if(isKeyword)
			return Keyword.intern(sym);
		return sym;
		}
	else
		{
		Matcher am = arraySymbolPat.matcher(s);
		if(am.matches())
			return Symbol.intern(am.group(1), am.group(2));
		}
	return null;
}


private static Object matchNumber(String s){
	Matcher m = intPat.matcher(s);
	if(m.matches())
		{
		if(m.group(2) != null)
			{
			if(m.group(8) != null)
				return BigInt.ZERO;
			return Numbers.num(0);
			}
		boolean negate = (m.group(1).equals("-"));
		String n;
		int radix = 10;
		if((n = m.group(3)) != null)
			radix = 10;
		else if((n = m.group(4)) != null)
			radix = 16;
		else if((n = m.group(5)) != null)
			radix = 8;
		else if((n = m.group(7)) != null)
			radix = Integer.parseInt(m.group(6));
		if(n == null)
			return null;
		BigInteger bn = new BigInteger(n, radix);
		if(negate)
			bn = bn.negate();
		if(m.group(8) != null)
			return BigInt.fromBigInteger(bn);
		return bn.bitLength() < 64 ?
		       Numbers.num(bn.longValue())
		                           : BigInt.fromBigInteger(bn);
		}
	m = floatPat.matcher(s);
	if(m.matches())
		{
		if(m.group(4) != null)
			return new BigDecimal(m.group(1));
		return Double.parseDouble(s);
		}
	m = ratioPat.matcher(s);
	if(m.matches())
		{
		String numerator = m.group(1);
		if (numerator.startsWith("+")) numerator = numerator.substring(1);

		return Numbers.divide(Numbers.reduceBigInt(BigInt.fromBigInteger(new BigInteger(numerator))),
		                      Numbers.reduceBigInt(BigInt.fromBigInteger(new BigInteger(m.group(2)))));
		}
	return null;
}

static private IFn getMacro(int ch){
	if(ch < macros.length)
		return macros[ch];
	return null;
}

static private boolean isMacro(int ch){
	return (ch < macros.length && macros[ch] != null);
}

static private boolean isTerminatingMacro(int ch){
	return (ch != '#' && ch != '\'' && ch != '%' && isMacro(ch));
}

public static class RegexReader extends AFn{
	static StringReader stringrdr = new StringReader();

	public Object invoke(Object reader, Object doublequote, Object opts, Object pendingForms) {
		StringBuilder sb = new StringBuilder();
		Reader r = (Reader) reader;
		for(int ch = read1(r); ch != '"'; ch = read1(r))
			{
			if(ch == -1)
				throw Util.runtimeException("EOF while reading regex");
			sb.append( (char) ch );
			if(ch == '\\')	//escape
				{
				ch = read1(r);
				if(ch == -1)
					throw Util.runtimeException("EOF while reading regex");
				sb.append( (char) ch ) ;
				}
			}
		return Pattern.compile(sb.toString());
	}
}

public static class StringReader extends AFn{
	public Object invoke(Object reader, Object doublequote, Object opts, Object pendingForms) {
		StringBuilder sb = new StringBuilder();
		Reader r = (Reader) reader;

		for(int ch = read1(r); ch != '"'; ch = read1(r))
			{
			if(ch == -1)
				throw Util.runtimeException("EOF while reading string");
			if(ch == '\\')	//escape
				{
				ch = read1(r);
				if(ch == -1)
					throw Util.runtimeException("EOF while reading string");
				switch(ch)
					{
					case 't':
						ch = '\t';
						break;
					case 'r':
						ch = '\r';
						break;
					case 'n':
						ch = '\n';
						break;
					case '\\':
						break;
					case '"':
						break;
					case 'b':
						ch = '\b';
						break;
					case 'f':
						ch = '\f';
						break;
					case 'u':
					{
					ch = read1(r);
					if (Character.digit(ch, 16) == -1)
						throw Util.runtimeException("Invalid unicode escape: \\u" + (char) ch);
					ch = readUnicodeChar((PushbackReader) r, ch, 16, 4, true);
					break;
					}
					default:
					{
					if(Character.isDigit(ch))
						{
						ch = readUnicodeChar((PushbackReader) r, ch, 8, 3, false);
						if(ch > 0377)
							throw Util.runtimeException("Octal escape sequence must be in range [0, 377].");
						}
					else
						throw Util.runtimeException("Unsupported escape character: \\" + (char) ch);
					}
					}
				}
			sb.append((char) ch);
			}
		return sb.toString();
	}
}

public static class CommentReader extends AFn{
	public Object invoke(Object reader, Object semicolon, Object opts, Object pendingForms) {
		Reader r = (Reader) reader;
		int ch;
		do
			{
			ch = read1(r);
			} while(ch != -1 && ch != '\n' && ch != '\r');
		return r;
	}

}

public static class DiscardReader extends AFn{
	public Object invoke(Object reader, Object underscore, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;
		read(r, true, null, true, opts, ensurePending(pendingForms));
		return r;
	}
}

// :a.b{:c 1} => {:a.b/c 1}
// ::{:c 1}   => {:a.b/c 1}  (where *ns* = a.b)
// ::a{:c 1}  => {:a.b/c 1}  (where a is aliased to a.b)
public static class NamespaceMapReader extends AFn{
	public Object invoke(Object reader, Object colon, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;

		boolean auto = false;
		int autoChar = read1(r);
		if(autoChar == ':')
			auto = true;
		else
		    unread(r, autoChar);

		Object sym = null;
		int nextChar = read1(r);
		if(isWhitespace(nextChar)) {  // the #:: { } case or an error
			if(auto) {
				while (isWhitespace(nextChar))
					nextChar = read1(r);
				if(nextChar != '{') {
					unread(r, nextChar);
					throw Util.runtimeException("Namespaced map must specify a namespace");
				}
			} else {
				unread(r, nextChar);
				throw Util.runtimeException("Namespaced map must specify a namespace");
			}
		} else if(nextChar != '{') {  // #:foo { } or #::foo { }
			unread(r, nextChar);
			sym = read(r, true, null, false, opts, pendingForms);
			nextChar = read1(r);
			while(isWhitespace(nextChar))
				nextChar = read1(r);
		}
		if(nextChar != '{')
			throw Util.runtimeException("Namespaced map must specify a map");

		// Resolve autoresolved ns
		String ns;
		if (auto) {
            Resolver resolver = (Resolver) TruffleReader.READER_RESOLVER.deref();
			if (sym == null) {
                if(resolver != null)
                    ns = resolver.currentNS().name;
                else
				    ns = Compiler.currentNS().getName().getName();
			} else if (!(sym instanceof Symbol) || ((Symbol)sym).getNamespace() != null) {
				throw Util.runtimeException("Namespaced map must specify a valid namespace: " + sym);
			} else {
				Symbol resolvedNS;
				if (resolver != null)
                    resolvedNS = resolver.resolveAlias((Symbol) sym);
				else{
                    Namespace rns = Compiler.currentNS().lookupAlias((Symbol)sym);
                    resolvedNS = rns != null?rns.getName():null;
                }

				if(resolvedNS == null) {
					throw Util.runtimeException("Unknown auto-resolved namespace alias: " + sym);
				} else {
					ns = resolvedNS.getName();
				}
			}
		} else if (!(sym instanceof Symbol) || ((Symbol)sym).getNamespace() != null) {
			throw Util.runtimeException("Namespaced map must specify a valid namespace: " + sym);
		} else {
			ns = ((Symbol)sym).getName();
		}

		// Read map
		List kvs = readDelimitedList('}', r, true, opts, ensurePending(pendingForms));
		if((kvs.size() & 1) == 1)
			throw Util.runtimeException("Namespaced map literal must contain an even number of forms");

		// Construct output map
		Object[] a = new Object[kvs.size()];
		Iterator iter = kvs.iterator();
		for(int i = 0; iter.hasNext(); i += 2) {
			Object key = iter.next();
			Object val = iter.next();

			if(key instanceof Keyword) {
				Keyword kw = (Keyword) key;
				if (kw.getNamespace() == null) {
					key = Keyword.intern(ns, kw.getName());
				} else if (kw.getNamespace().equals("_")) {
					key = Keyword.intern(null, kw.getName());
				}
			} else if(key instanceof Symbol) {
				Symbol s = (Symbol) key;
				if (s.getNamespace() == null) {
					key = Symbol.intern(ns, s.getName());
				} else if (s.getNamespace().equals("_")) {
					key = Symbol.intern(null, s.getName());
				}
			}
			a[i] = key;
			a[i+1] = val;
		}
		return TruffleReader.map(a);
	}
}


public static class SymbolicValueReader extends AFn{

    static IPersistentMap  specials = PersistentHashMap.create(Symbol.intern("Inf"), Double.POSITIVE_INFINITY,
                                                               Symbol.intern("-Inf"), Double.NEGATIVE_INFINITY,
                                                               Symbol.intern("NaN"), Double.NaN);

	public Object invoke(Object reader, Object quote, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;
		Object o = read(r, true, null, true, opts, ensurePending(pendingForms));

		if (!(o instanceof Symbol))
			throw Util.runtimeException("Invalid token: ##" + o);
		if (!(specials.containsKey(o)))
			throw Util.runtimeException("Unknown symbolic value: ##" + o);

		return specials.valAt(o);
	}
}

public static class WrappingReader extends AFn{
	final Symbol sym;

	public WrappingReader(Symbol sym){
		this.sym = sym;
	}

	public Object invoke(Object reader, Object quote, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;
		Object o = read(r, true, null, true, opts, ensurePending(pendingForms));
		return TruffleReader.list(sym, o);
	}

}

public static class DeprecatedWrappingReader extends AFn{
	final Symbol sym;
	final String macro;

	public DeprecatedWrappingReader(Symbol sym, String macro){
		this.sym = sym;
		this.macro = macro;
	}

	public Object invoke(Object reader, Object quote, Object opts, Object pendingForms) {
		System.out.println("WARNING: reader macro " + macro +
		                   " is deprecated; use " + sym.getName() +
		                   " instead");
		PushbackReader r = (PushbackReader) reader;
		Object o = read(r, true, null, true, opts, ensurePending(pendingForms));
		return TruffleReader.list(sym, o);
	}

}

public static class VarReader extends AFn{
	public Object invoke(Object reader, Object quote, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;
		Object o = read(r, true, null, true, opts, ensurePending(pendingForms));
//		if(o instanceof Symbol)
//			{
//			Object v = Compiler.maybeResolveIn(Compiler.currentNS(), (Symbol) o);
//			if(v instanceof Var)
//				return v;
//			}
		return TruffleReader.list(THE_VAR, o);
	}
}

/*
static class DerefReader extends AFn{

	public Object invoke(Object reader, Object quote) {
		PushbackReader r = (PushbackReader) reader;
		int ch = read1(r);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		if(ch == '!')
			{
			Object o = read(r, true, null, true);
			return TruffleReader.list(DEREF_BANG, o);
			}
		else
			{
			r.unread(ch);
			Object o = read(r, true, null, true);
			return TruffleReader.list(DEREF, o);
			}
	}

}
*/

public static class DispatchReader extends AFn{
	public Object invoke(Object reader, Object hash, Object opts, Object pendingForms) {
		int ch = read1((Reader) reader);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		IFn fn = dispatchMacros[ch];

		// Try the ctor reader first
		if(fn == null) {
		unread((PushbackReader) reader, ch);
		pendingForms = ensurePending(pendingForms);
		Object result = ctorReader.invoke(reader, ch, opts, pendingForms);

		if(result != null)
			return result;
		else
			throw Util.runtimeException(String.format("No dispatch macro for: %c", (char) ch));
		}
		return fn.invoke(reader, ch, opts, pendingForms);
	}
}

static Symbol garg(int n){
	return Symbol.intern(null, (n == -1 ? "rest" : ("p" + n)) + "__" + TruffleReader.nextID() + "#");
}

public static class FnReader extends AFn{
	public Object invoke(Object reader, Object lparen, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;
		if(ARG_ENV.deref() != null)
			throw new IllegalStateException("Nested #()s are not allowed");
		try
			{
			Var.pushThreadBindings(
					TruffleReader.map(ARG_ENV, PersistentTreeMap.EMPTY));
			unread(r, '(');
			Object form = read(r, true, null, true, opts, ensurePending(pendingForms));

			PersistentVector args = PersistentVector.EMPTY;
			PersistentTreeMap argsyms = (PersistentTreeMap) ARG_ENV.deref();
			ISeq rargs = argsyms.rseq();
			if(rargs != null)
				{
				int higharg = (Integer) ((Map.Entry) rargs.first()).getKey();
				if(higharg > 0)
					{
					for(int i = 1; i <= higharg; ++i)
						{
						Object sym = argsyms.valAt(i);
						if(sym == null)
							sym = garg(i);
						args = args.cons(sym);
						}
					}
				Object restsym = argsyms.valAt(-1);
				if(restsym != null)
					{
					args = args.cons(Compiler._AMP_);
					args = args.cons(restsym);
					}
				}
			return TruffleReader.list(Compiler.FN, args, form);
			}
		finally
			{
			Var.popThreadBindings();
			}
	}
}

static Symbol registerArg(int n){
	PersistentTreeMap argsyms = (PersistentTreeMap) ARG_ENV.deref();
	if(argsyms == null)
		{
		throw new IllegalStateException("arg literal not in #()");
		}
	Symbol ret = (Symbol) argsyms.valAt(n);
	if(ret == null)
		{
		ret = garg(n);
		ARG_ENV.set(argsyms.assoc(n, ret));
		}
	return ret;
}

static class ArgReader extends AFn{
	public Object invoke(Object reader, Object pct, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;
		String token = readToken(r, '%');
		if(ARG_ENV.deref() == null)
			return interpretToken(token, null);

		Matcher m = argPat.matcher(token);
		if (!m.matches())
			throw new IllegalStateException("arg literal must be %, %& or %integer");
		if (m.group(1) != null) // %&
			return registerArg(-1);
		return registerArg(m.group(2) == null ? 1 : Integer.parseInt(m.group(2)));
	}
}

public static class MetaReader extends AFn{
	public Object invoke(Object reader, Object caret, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;
		int line = -1;
		int column = -1;
		if(r instanceof LineNumberingPushbackReader)
			{
			line = ((LineNumberingPushbackReader) r).getLineNumber();
			column = ((LineNumberingPushbackReader) r).getColumnNumber()-1;
			}
		pendingForms = ensurePending(pendingForms);
		Object meta = read(r, true, null, true, opts, pendingForms);
		if(meta instanceof Symbol || meta instanceof String)
			meta = TruffleReader.map(TruffleReader.TAG_KEY, meta);
		else if (meta instanceof Keyword)
			meta = TruffleReader.map(meta, TruffleReader.T);
		else if (meta instanceof IPersistentVector)
			meta = TruffleReader.map(TruffleReader.PARAM_TAGS_KEY, meta);
		else if(!(meta instanceof IPersistentMap))
			throw new IllegalArgumentException("Metadata must be Symbol,Keyword,String,Vector or Map");

		Object o = read(r, true, null, true, opts, pendingForms);
		if(o instanceof IMeta)
			{
			if(line != -1 && o instanceof ISeq)
				{
				meta = TruffleReader.assoc(meta, TruffleReader.LINE_KEY, TruffleReader.get(meta, TruffleReader.LINE_KEY, line));
				meta = TruffleReader.assoc(meta, TruffleReader.COLUMN_KEY, TruffleReader.get(meta,TruffleReader.COLUMN_KEY, column));
				}
			if(o instanceof IReference)
				{
				((IReference)o).resetMeta((IPersistentMap) meta);
				return o;
				}
			Object ometa = TruffleReader.meta(o);
			for(ISeq s = TruffleReader.seq(meta); s != null; s = s.next()) {
			IMapEntry kv = (IMapEntry) s.first();
			ometa = TruffleReader.assoc(ometa, kv.getKey(), kv.getValue());
			}
			return ((IObj) o).withMeta((IPersistentMap) ometa);
			}
		else
			throw new IllegalArgumentException("Metadata can only be applied to IMetas");
	}

}

public static class SyntaxQuoteReader extends AFn{
	public Object invoke(Object reader, Object backquote, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;
		try
			{
			Var.pushThreadBindings(
					TruffleReader.map(GENSYM_ENV, PersistentHashMap.EMPTY));

			Object form = read(r, true, null, true, opts, ensurePending(pendingForms));
			return syntaxQuote(form);
			}
		finally
			{
			Var.popThreadBindings();
			}
	}

	static Object syntaxQuote(Object form) {
		Object ret;
		if(Compiler.isSpecial(form))
			ret = TruffleReader.list(Compiler.QUOTE, form);
		else if(form instanceof Symbol)
			{
            Resolver resolver = (Resolver) TruffleReader.READER_RESOLVER.deref();
			Symbol sym = (Symbol) form;
			if(sym.ns == null && sym.name.endsWith("#"))
				{
				IPersistentMap gmap = (IPersistentMap) GENSYM_ENV.deref();
				if(gmap == null)
					throw new IllegalStateException("Gensym literal not in syntax-quote");
				Symbol gs = (Symbol) gmap.valAt(sym);
				if(gs == null)
					GENSYM_ENV.set(gmap.assoc(sym, gs = Symbol.intern(null,
					                                                  sym.name.substring(0, sym.name.length() - 1)
					                                                  + "__" + TruffleReader.nextID() + "__auto__")));
				sym = gs;
				}
			else if(sym.ns == null && sym.name.endsWith("."))
				{
				Symbol csym = Symbol.intern(null, sym.name.substring(0, sym.name.length() - 1));
				if(resolver != null){
                    Symbol rc = resolver.resolveClass(csym);
                    if(rc != null)
                        csym = rc;
                }
				else
				    csym = Compiler.resolveSymbol(csym);
				sym = Symbol.intern(null, csym.name.concat("."));
				}
			else if(sym.ns == null && sym.name.startsWith("."))
				{
				// Simply quote method names.
				}
            else if(resolver != null)
                {
                Symbol nsym = null;
                if(sym.ns != null){
                    Symbol alias = Symbol.intern(null, sym.ns);
                    nsym = resolver.resolveClass(alias);
                    if(nsym == null)
                        nsym = resolver.resolveAlias(alias);
                    }
                if(nsym != null){
                    // Classname/foo -> package.qualified.Classname/foo
                    sym = Symbol.intern(nsym.name, sym.name);
                    }
                else if(sym.ns == null){
                    Symbol rsym = resolver.resolveClass(sym);
                    if(rsym == null)
                        rsym = resolver.resolveVar(sym);
                    if(rsym != null)
                        sym = rsym;
                    else
                        sym = Symbol.intern(resolver.currentNS().name,sym.name);
                }
                //leave alone if qualified
                }
			else
				{
				Object maybeClass = null;
				if(sym.ns != null)
					maybeClass = Compiler.currentNS().getMapping(
							Symbol.intern(null, sym.ns));
				if(maybeClass instanceof Class)
					{
					// Classname/foo -> package.qualified.Classname/foo
					sym = Symbol.intern(
							((Class)maybeClass).getName(), sym.name);
					}
				else
					sym = Compiler.resolveSymbol(sym);
				}
			ret = TruffleReader.list(Compiler.QUOTE, sym);
			}
		else if(isUnquote(form))
			return TruffleReader.second(form);
		else if(isUnquoteSplicing(form))
			throw new IllegalStateException("splice not in list");
		else if(form instanceof IPersistentCollection)
			{
			if(form instanceof IRecord)
				ret = form;
			else if(form instanceof IPersistentMap)
				{
				IPersistentVector keyvals = flattenMap(form);
				ret = TruffleReader.list(APPLY, HASHMAP, TruffleReader.list(SEQ, TruffleReader.cons(CONCAT, sqExpandList(keyvals.seq()))));
				}
			else if(form instanceof IPersistentVector)
				{
				ret = TruffleReader.list(APPLY, VECTOR, TruffleReader.list(SEQ, TruffleReader.cons(CONCAT, sqExpandList(((IPersistentVector) form).seq()))));
				}
			else if(form instanceof IPersistentSet)
				{
				ret = TruffleReader.list(APPLY, HASHSET, TruffleReader.list(SEQ, TruffleReader.cons(CONCAT, sqExpandList(((IPersistentSet) form).seq()))));
				}
			else if(form instanceof ISeq || form instanceof IPersistentList)
				{
				ISeq seq = TruffleReader.seq(form);
				if(seq == null)
					ret = TruffleReader.cons(LIST,null);
				else
					ret = TruffleReader.list(SEQ, TruffleReader.cons(CONCAT, sqExpandList(seq)));
				}
			else
				throw new UnsupportedOperationException("Unknown Collection type");
			}
		else if(form instanceof Keyword
		        || form instanceof Number
		        || form instanceof Character
		        || form instanceof String)
			ret = form;
		else
			ret = TruffleReader.list(Compiler.QUOTE, form);

		if(form instanceof IObj && TruffleReader.meta(form) != null)
			{
			//filter line and column numbers
			IPersistentMap newMeta = ((IObj) form).meta().without(TruffleReader.LINE_KEY).without(TruffleReader.COLUMN_KEY);
			if(newMeta.count() > 0)
				return TruffleReader.list(WITH_META, ret, syntaxQuote(((IObj) form).meta()));
			}
		return ret;
	}

	private static ISeq sqExpandList(ISeq seq) {
		PersistentVector ret = PersistentVector.EMPTY;
		for(; seq != null; seq = seq.next())
			{
			Object item = seq.first();
			if(isUnquote(item))
				ret = ret.cons(TruffleReader.list(LIST, TruffleReader.second(item)));
			else if(isUnquoteSplicing(item))
				ret = ret.cons(TruffleReader.second(item));
			else
				ret = ret.cons(TruffleReader.list(LIST, syntaxQuote(item)));
			}
		return ret.seq();
	}

	private static IPersistentVector flattenMap(Object form){
		IPersistentVector keyvals = PersistentVector.EMPTY;
		for(ISeq s = TruffleReader.seq(form); s != null; s = s.next())
			{
			IMapEntry e = (IMapEntry) s.first();
			keyvals = (IPersistentVector) keyvals.cons(e.key());
			keyvals = (IPersistentVector) keyvals.cons(e.val());
			}
		return keyvals;
	}

}

static boolean isUnquoteSplicing(Object form){
	return form instanceof ISeq && Util.equals(TruffleReader.first(form),UNQUOTE_SPLICING);
}

static boolean isUnquote(Object form){
	return form instanceof ISeq && Util.equals(TruffleReader.first(form),UNQUOTE);
}

static class UnquoteReader extends AFn{
	public Object invoke(Object reader, Object comma, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;
		int ch = read1(r);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		pendingForms = ensurePending(pendingForms);
		if(ch == '@')
			{
			Object o = read(r, true, null, true, opts, pendingForms);
			return TruffleReader.list(UNQUOTE_SPLICING, o);
			}
		else
			{
			unread(r, ch);
			Object o = read(r, true, null, true, opts, pendingForms);
			return TruffleReader.list(UNQUOTE, o);
			}
	}

}

public static class CharacterReader extends AFn{
	public Object invoke(Object reader, Object backslash, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;
		int ch = read1(r);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		String token = readToken(r, (char) ch);
		if(token.length() == 1)
			return Character.valueOf(token.charAt(0));
		else if(token.equals("newline"))
			return '\n';
		else if(token.equals("space"))
			return ' ';
		else if(token.equals("tab"))
			return '\t';
		else if(token.equals("backspace"))
			return '\b';
		else if(token.equals("formfeed"))
			return '\f';
		else if(token.equals("return"))
			return '\r';
		else if(token.startsWith("u"))
			{
			char c = (char) readUnicodeChar(token, 1, 4, 16);
			if(c >= '\uD800' && c <= '\uDFFF') // surrogate code unit?
				throw Util.runtimeException("Invalid character constant: \\u" + Integer.toString(c, 16));
			return c;
			}
		else if(token.startsWith("o"))
			{
			int len = token.length() - 1;
			if(len > 3)
				throw Util.runtimeException("Invalid octal escape sequence length: " + len);
			int uc = readUnicodeChar(token, 1, len, 8);
			if(uc > 0377)
				throw Util.runtimeException("Octal escape sequence must be in range [0, 377].");
			return (char) uc;
			}
		throw Util.runtimeException("Unsupported character: \\" + token);
	}

}

public static class ListReader extends AFn{
	public Object invoke(Object reader, Object leftparen, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;
		int line = -1;
		int column = -1;
		if(r instanceof LineNumberingPushbackReader)
			{
			line = ((LineNumberingPushbackReader) r).getLineNumber();
			column = ((LineNumberingPushbackReader) r).getColumnNumber()-1;
			}
		List list = readDelimitedList(')', r, true, opts, ensurePending(pendingForms));
		if(list.isEmpty())
			return PersistentList.EMPTY;
		IObj s = (IObj) PersistentList.create(list);
//		IObj s = (IObj) TruffleReader.seq(list);
		if(line != -1)
			{
			Object meta = TruffleReader.meta(s);
			meta = TruffleReader.assoc(meta, TruffleReader.LINE_KEY, TruffleReader.get(meta, TruffleReader.LINE_KEY, line));
			meta = TruffleReader.assoc(meta, TruffleReader.COLUMN_KEY, TruffleReader.get(meta,TruffleReader.COLUMN_KEY, column));
			return s.withMeta((IPersistentMap)meta);
			}
		else
			return s;
	}

}

/*
static class CtorReader extends AFn{
	static final Symbol cls = Symbol.intern("class");

	public Object invoke(Object reader, Object leftangle) {
		PushbackReader r = (PushbackReader) reader;
		// #<class classname>
		// #<classname args*>
		// #<classname/staticMethod args*>
		List list = readDelimitedList('>', r, true);
		if(list.isEmpty())
			throw Util.runtimeException("Must supply 'class', classname or classname/staticMethod");
		Symbol s = (Symbol) list.get(0);
		Object[] args = list.subList(1, list.size()).toArray();
		if(s.equals(cls))
			{
			return TruffleReader.classForName(args[0].toString());
			}
		else if(s.ns != null) //static method
			{
			String classname = s.ns;
			String method = s.name;
			return Reflector.invokeStaticMethod(classname, method, args);
			}
		else
			{
			return Reflector.invokeConstructor(TruffleReader.classForName(s.name), args);
			}
	}
}
*/

public static class EvalReader extends AFn{
	public Object invoke(Object reader, Object eq, Object opts, Object pendingForms) {
		if (!TruffleReader.booleanCast(TruffleReader.READEVAL.deref()))
			{
			throw Util.runtimeException("EvalReader not allowed when *read-eval* is false.");
			}

		PushbackReader r = (PushbackReader) reader;
		Object o = read(r, true, null, true, opts, ensurePending(pendingForms));
		if(o instanceof Symbol)
			{
			return TruffleReader.classForName(o.toString());
			}
		else if(o instanceof IPersistentList)
			{
			Symbol fs = (Symbol) TruffleReader.first(o);
			if(fs.equals(THE_VAR))
				{
				Symbol vs = (Symbol) TruffleReader.second(o);
				return TruffleReader.var(vs.ns, vs.name);  //Compiler.resolve((Symbol) TruffleReader.second(o),true);
				}
			if(fs.name.endsWith("."))
				{
				Object[] args = TruffleReader.toArray(TruffleReader.next(o));
				return Reflector.invokeConstructor(TruffleReader.classForName(fs.name.substring(0, fs.name.length() - 1)), args);
				}
			if(Compiler.namesStaticMember(fs))
				{
				Object[] args = TruffleReader.toArray(TruffleReader.next(o));
				return Reflector.invokeStaticMethod(fs.ns, fs.name, args);
				}
			Object v = Compiler.maybeResolveIn(Compiler.currentNS(), fs);
			if(v instanceof Var)
				{
				if(((Var)v).isBound())
					return ((IFn) v).applyTo(TruffleReader.next(o));
				// Var is unbound — fall through to readEvalHook
				}
			// Try Truffle runtime hook for unbound vars or unresolved symbols
			if(readEvalHook != null)
				{
				return readEvalHook.apply((IPersistentList) o);
				}
			throw Util.runtimeException("Can't resolve " + fs);
			}
		else
			throw new IllegalArgumentException("Unsupported #= form");
	}
}

//static class ArgVectorReader extends AFn{
//	public Object invoke(Object reader, Object leftparen) {
//		PushbackReader r = (PushbackReader) reader;
//		return ArgVector.create(readDelimitedList('|', r, true));
//	}
//
//}

public static class VectorReader extends AFn{
	public Object invoke(Object reader, Object leftparen, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;
		return LazilyPersistentVector.create(readDelimitedList(']', r, true, opts, ensurePending(pendingForms)));
	}

}

public static class MapReader extends AFn{
	public Object invoke(Object reader, Object leftparen, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;
		Object[] a = readDelimitedList('}', r, true, opts, ensurePending(pendingForms)).toArray();
		if((a.length & 1) == 1)
			throw Util.runtimeException("Map literal must contain an even number of forms");
		return TruffleReader.map(a);
	}

}

public static class SetReader extends AFn{
	public Object invoke(Object reader, Object leftbracket, Object opts, Object pendingForms) {
		PushbackReader r = (PushbackReader) reader;
		return PersistentHashSet.createWithCheck(readDelimitedList('}', r, true, opts, ensurePending(pendingForms)));
	}

}

public static class UnmatchedDelimiterReader extends AFn{
	public Object invoke(Object reader, Object rightdelim, Object opts, Object pendingForms) {
		throw Util.runtimeException("Unmatched delimiter: " + rightdelim);
	}

}

public static class UnreadableReader extends AFn{
	public Object invoke(Object reader, Object leftangle, Object opts, Object pendingForms) {
		throw Util.runtimeException("Unreadable form");
	}
}

// Sentinel values for reading lists
private static final Object READ_EOF = new Object();
private static final Object READ_FINISHED = new Object();

public static List readDelimitedList(char delim, PushbackReader r, boolean isRecursive, Object opts, Object pendingForms) {
	final int firstline =
			(r instanceof LineNumberingPushbackReader) ?
			((LineNumberingPushbackReader) r).getLineNumber() : -1;

	ArrayList a = new ArrayList();
	Resolver resolver = (Resolver) TruffleReader.READER_RESOLVER.deref();

	for(; ;) {

		Object form = read(r, false, READ_EOF, delim, READ_FINISHED, isRecursive, opts, pendingForms,
                           resolver);

		if (form == READ_EOF) {
			if (firstline < 0)
				throw Util.runtimeException("EOF while reading");
			else
				throw Util.runtimeException("EOF while reading, starting at line " + firstline);
		} else if (form == READ_FINISHED) {
			return a;
		}

		a.add(form);
	}
}

public static class CtorReader extends AFn{
	public Object invoke(Object reader, Object firstChar, Object opts, Object pendingForms){
		PushbackReader r = (PushbackReader) reader;
		pendingForms = ensurePending(pendingForms);
		Object name = read(r, true, null, false, opts, pendingForms);
		if (!(name instanceof Symbol))
			throw new RuntimeException("Reader tag must be a symbol");
		Symbol sym = (Symbol)name;
		Object form = read(r, true, null, true, opts, pendingForms);

		if(isPreserveReadCond(opts) || TruffleReader.suppressRead()) {
			return TaggedLiteral.create(sym, form);
		} else {
			return sym.getName().contains(".") ? readRecord(form, sym, opts, pendingForms) : readTagged(form, sym, opts, pendingForms);
		}

	}

	private Object readTagged(Object o, Symbol tag, Object opts, Object pendingForms){

		ILookup data_readers = (ILookup)TruffleReader.DATA_READERS.deref();
		IFn data_reader = (IFn)TruffleReader.get(data_readers, tag);
		if(data_reader == null){
		data_readers = (ILookup)TruffleReader.DEFAULT_DATA_READERS.deref();
		data_reader = (IFn)TruffleReader.get(data_readers, tag);
		if(data_reader == null){
		IFn default_reader = (IFn)TruffleReader.DEFAULT_DATA_READER_FN.deref();
		if(default_reader != null)
			return default_reader.invoke(tag, o);
		else
			throw new RuntimeException("No reader function for tag " + tag.toString());
		}
		}

		return data_reader.invoke(o);
	}

	private Object readRecord(Object form, Symbol recordName, Object opts, Object pendingForms){
        boolean readeval = TruffleReader.booleanCast(TruffleReader.READEVAL.deref());

	    if(!readeval)
		    {
		    throw Util.runtimeException("Record construction syntax can only be used when *read-eval* == true");
		    }

		Class recordClass = TruffleReader.classForNameNonLoading(recordName.toString());


		boolean shortForm = true;

		if(form instanceof IPersistentMap) {
			shortForm = false;
		} else if (form instanceof IPersistentVector) {
			shortForm = true;
		} else {
			throw Util.runtimeException("Unreadable constructor form starting with \"#" + recordName + "\"");
		}

		Object ret = null;
		Constructor[] allctors = ((Class)recordClass).getConstructors();

		if(shortForm)
			{
	        IPersistentVector recordEntries = (IPersistentVector)form;
			boolean ctorFound = false;
			for (Constructor ctor : allctors)
				if(ctor.getParameterTypes().length == recordEntries.count())
					ctorFound = true;

			if(!ctorFound)
				throw Util.runtimeException("Unexpected number of constructor arguments to " + recordClass.toString() + ": got " + recordEntries.count());

			ret = Reflector.invokeConstructor(recordClass, TruffleReader.toArray(recordEntries));
			}
		else
			{

			IPersistentMap vals = (IPersistentMap)form;
			for(ISeq s = TruffleReader.keys(vals); s != null; s = s.next())
				{
				if(!(s.first() instanceof Keyword))
					throw Util.runtimeException("Unreadable defrecord form: key must be of type clojure.lang.Keyword, got " + s.first().toString());
				}
			ret = Reflector.invokeStaticMethod(recordClass, "create", new Object[]{vals});
			}

		return ret;
	}
}

static boolean isPreserveReadCond(Object opts) {
	if(TruffleReader.booleanCast(READ_COND_ENV.deref()) && opts instanceof IPersistentMap)
    {
        Object readCond = ((IPersistentMap) opts).valAt(OPT_READ_COND);
        return COND_PRESERVE.equals(readCond);
    }
    else
        return false;
}

public static class ConditionalReader extends AFn {

	final static private Object READ_STARTED = new Object();
	final static public Keyword DEFAULT_FEATURE = Keyword.intern(null, "default");
	final static public IPersistentSet RESERVED_FEATURES =
		TruffleReader.set(Keyword.intern(null, "else"), Keyword.intern(null, "none"));

	public static boolean hasFeature(Object feature, Object opts) {
		if (! (feature instanceof Keyword))
			throw Util.runtimeException("Feature should be a keyword: " + feature);

		if(DEFAULT_FEATURE.equals(feature))
			return true;

		IPersistentSet custom = (IPersistentSet) ((IPersistentMap)opts).valAt(OPT_FEATURES);
		return custom != null && custom.contains(feature);
	}

	public static Object readCondDelimited(PushbackReader r, boolean splicing, Object opts, Object pendingForms) {
		Object result = READ_STARTED;
		Object form; // The most recently ready form
		boolean toplevel = (pendingForms == null);
		pendingForms = ensurePending(pendingForms);

		final int firstline =
				(r instanceof LineNumberingPushbackReader) ?
						((LineNumberingPushbackReader) r).getLineNumber() : -1;

		for(; ;) {
			if(result == READ_STARTED) {
				// Read the next feature
				form = read(r, false, READ_EOF, ')', READ_FINISHED, true, opts, pendingForms, null);

				if (form == READ_EOF) {
					if (firstline < 0)
						throw Util.runtimeException("EOF while reading");
					else
						throw Util.runtimeException("EOF while reading, starting at line " + firstline);
				} else if (form == READ_FINISHED) {
					break; // read-cond form is done
				}

				if(RESERVED_FEATURES.contains(form))
					throw Util.runtimeException("Feature name " + form + " is reserved.");

				if (hasFeature(form, opts)) {

					//Read the form corresponding to the feature, and assign it to result if everything is kosher

					form = read(r, false, READ_EOF, ')', READ_FINISHED, true, opts, pendingForms, (Resolver) TruffleReader.READER_RESOLVER.deref());

					if (form == READ_EOF) {
						if (firstline < 0)
							throw Util.runtimeException("EOF while reading");
						else
							throw Util.runtimeException("EOF while reading, starting at line " + firstline);
					} else if (form == READ_FINISHED) {
						if (firstline < 0)
							throw Util.runtimeException("read-cond requires an even number of forms.");
						else
							throw Util.runtimeException("read-cond starting on line " + firstline + " requires an even number of forms");
					} else {
						result = form;
					}
				}
			}

			// When we already have a result, or when the feature didn't match, discard the next form in the reader
			try {
				Var.pushThreadBindings(TruffleReader.map(TruffleReader.SUPPRESS_READ, TruffleReader.T));
				form = read(r, false, READ_EOF, ')', READ_FINISHED, true, opts, pendingForms, (Resolver) TruffleReader.READER_RESOLVER.deref());

				if (form == READ_EOF) {
					if (firstline < 0)
						throw Util.runtimeException("EOF while reading");
					else
						throw Util.runtimeException("EOF while reading, starting at line " + firstline);
				} else if (form == READ_FINISHED) {
					break;
				}
			}
			finally {
				Var.popThreadBindings();
			}

		}

		if (result == READ_STARTED)  // no features matched
            return r;

		if (splicing) {
			if(! (result instanceof List))
				throw Util.runtimeException("Spliced form list in read-cond-splicing must implement java.util.List");

			if(toplevel)
				throw Util.runtimeException("Reader conditional splicing not allowed at the top level.");

			((List)pendingForms).addAll(0, (List)result);

			return r;
		} else {
			return result;
		}
	};

    private static void checkConditionalAllowed(Object opts) {
        IPersistentMap mopts = (IPersistentMap)opts;
        if(! (opts != null && (COND_ALLOW.equals(mopts.valAt(OPT_READ_COND)) ||
                               COND_PRESERVE.equals(mopts.valAt(OPT_READ_COND)))))
            throw Util.runtimeException("Conditional read not allowed");
    }

	public Object invoke(Object reader, Object mode, Object opts, Object pendingForms) {
		checkConditionalAllowed(opts);

		PushbackReader r = (PushbackReader) reader;
		int ch = read1(r);
		if (ch == -1)
			throw Util.runtimeException("EOF while reading character");

		boolean splicing = false;

		if (ch == '@') {
			splicing = true;
			ch = read1(r);
		}

		while(isWhitespace(ch))
			ch = read1(r);

		if (ch == -1)
			throw Util.runtimeException("EOF while reading character");

		if(ch != '(')
			throw Util.runtimeException("read-cond body must be a list");

		try {
			Var.pushThreadBindings(TruffleReader.map(READ_COND_ENV, TruffleReader.T));

			if (isPreserveReadCond(opts)) {
				IFn listReader = getMacro(ch); // should always be a list
				Object form = listReader.invoke(r, ch, opts, ensurePending(pendingForms));

				return ReaderConditional.create(form, splicing);
			} else {
				return readCondDelimited(r, splicing, opts, pendingForms);
			}
		} finally {
			Var.popThreadBindings();
		}
	}
}

/*
public static void main(String[] args) throws Exception{
	//TruffleReader.init();
	PushbackReader rdr = new PushbackReader( new java.io.StringReader( "(+ 21 21)" ) );
	Object input = LispReader.read(rdr, false, new Object(), false );
	System.out.println(Compiler.eval(input));
}

public static void main(String[] args){
	LineNumberingPushbackReader r = new LineNumberingPushbackReader(new InputStreamReader(System.in));
	OutputStreamWriter w = new OutputStreamWriter(System.out);
	Object ret = null;
	try
		{
		for(; ;)
			{
			ret = LispReader.read(r, true, null, false);
			TruffleReader.print(ret, w);
			w.write('\n');
			if(ret != null)
				w.write(ret.getClass().toString());
			w.write('\n');
			w.flush();
			}
		}
	catch(Exception e)
		{
		e.printStackTrace();
		}
}
 */

}
