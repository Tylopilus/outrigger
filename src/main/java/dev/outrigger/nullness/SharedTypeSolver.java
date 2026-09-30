package dev.outrigger.nullness;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;

/**
 * Lets several analyses use one long-lived type solver (a project's jars):
 * a type solver can only belong to one parent, so each analysis gets its own
 * of these in front of the shared one.
 */
final class SharedTypeSolver implements TypeSolver {

    private final TypeSolver shared;
    private TypeSolver parent;

    SharedTypeSolver(TypeSolver shared) {
        this.shared = shared;
    }

    @Override
    public TypeSolver getParent() {
        return parent;
    }

    @Override
    public void setParent(TypeSolver parent) {
        this.parent = parent;
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
        return shared.tryToSolveType(name);
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveTypeInModule(String module, String name) {
        return shared.tryToSolveTypeInModule(module, name);
    }
}
